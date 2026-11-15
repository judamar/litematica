package fi.dy.masa.litematica.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import io.netty.buffer.Unpooled;
import org.apache.commons.lang3.tuple.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import fi.dy.masa.malilib.network.PacketSplitter;
import fi.dy.masa.malilib.util.game.wrap.GameUtils;
import fi.dy.masa.malilib.util.game.wrap.NbtWrap;
import fi.dy.masa.malilib.util.nbt.NbtUtils;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.config.Configs;

/**
 * On a remote server the client never gets the contents of a container into its local tile entity,
 * vanilla only sends them to the container window. So the inventory previews (Litematica's and
 * Tweakeroo's, both read the client tile entity) are empty.
 * <br><br>
 * This asks the server for the NBT of the container the player looks at with a one layer
 * "litematica:save" request (the same protocol as the multiplayer schematic saving),
 * and reads the response into the tile entities of the client world.
 * <br><br>
 * The same goes for the entities with an inventory, such as minecarts with chests or hoppers: the player
 * looks at one, the entities around it are requested, and the items are put into the client entity.
 */
public class ServerContainerSync
{
    public static final ServerContainerSync INSTANCE = new ServerContainerSync();

    protected static final long REQUEST_INTERVAL_MS = 1000L;
    protected static final long REQUEST_TIMEOUT_MS = 5000L;
    protected static final int MAX_PENDING = 8;
    protected static final int MAX_FAILURES = 3;
    protected static final int MAX_TRACKED_POSITIONS = 16;

    /** The request covers a 3x1x3 area, so that the other half of a double chest is also included */
    protected static final int REGION_SIZE_X = 3;
    protected static final int REGION_SIZE_Z = 3;

    protected final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    protected final HashMap<Long, Long> lastRequestTimes = new HashMap<>();
    protected final HashMap<Integer, Long> lastEntityRequestTimes = new HashMap<>();
    protected volatile int failures;

    protected static class Pending
    {
        protected final BlockPos origin;
        protected final long sentTime;
        protected final boolean entities;

        protected Pending(BlockPos origin, long sentTime, boolean entities)
        {
            this.origin = origin;
            this.sentTime = sentTime;
            this.entities = entities;
        }
    }

    public void onTick(Minecraft mc)
    {
        RayTraceResult trace = mc.objectMouseOver;

        if (trace != null && trace.typeOfHit == RayTraceResult.Type.BLOCK)
        {
            this.request(trace.getBlockPos());
        }
        else if (trace != null && trace.typeOfHit == RayTraceResult.Type.ENTITY &&
                 trace.entityHit instanceof IInventory)
        {
            this.requestEntity(trace.entityHit);
        }
    }

    public void reset()
    {
        this.pending.clear();
        this.lastRequestTimes.clear();
        this.lastEntityRequestTimes.clear();
        this.failures = 0;
    }

    /**
     * Asks the server for the contents of the container at the given position, if it makes sense.
     * It is cheap to call this every frame, the requests are throttled here.
     */
    public void request(BlockPos pos)
    {
        Minecraft mc = GameUtils.getClient();
        NetHandlerPlayClient handler = mc.getConnection();
        WorldClient world = mc.world;

        if (handler == null || world == null || GameUtils.isSinglePlayer() ||
            Configs.Generic.SERVER_CONTAINER_PREVIEW.getBooleanValue() == false)
        {
            return;
        }

        long now = System.currentTimeMillis();
        this.expirePending(now);

        if (this.failures >= MAX_FAILURES ||
            this.pending.size() >= MAX_PENDING ||
            (world.getTileEntity(pos) instanceof IInventory) == false)
        {
            return;
        }

        Long last = this.lastRequestTimes.get(pos.toLong());

        if (last != null && now - last < REQUEST_INTERVAL_MS)
        {
            return;
        }

        if (this.lastRequestTimes.size() > MAX_TRACKED_POSITIONS)
        {
            this.lastRequestTimes.clear();
        }

        this.lastRequestTimes.put(pos.toLong(), now);

        UUID id = UUID.randomUUID();
        BlockPos origin = pos.toImmutable();
        this.pending.put(id, new Pending(origin, now, false));
        this.sendRequest(id, origin, false, handler);
    }

    /**
     * Asks the server for the entities around the given entity, which has an inventory, so that its contents
     * can be put into the client entity. Like {@link #request(BlockPos)}, it is cheap to call every frame.
     */
    public void requestEntity(Entity entity)
    {
        Minecraft mc = GameUtils.getClient();
        NetHandlerPlayClient handler = mc.getConnection();

        if (handler == null || mc.world == null || GameUtils.isSinglePlayer() ||
            Configs.Generic.SERVER_CONTAINER_PREVIEW.getBooleanValue() == false)
        {
            return;
        }

        long now = System.currentTimeMillis();
        this.expirePending(now);

        if (this.failures >= MAX_FAILURES || this.pending.size() >= MAX_PENDING)
        {
            return;
        }

        Long last = this.lastEntityRequestTimes.get(entity.getEntityId());

        if (last != null && now - last < REQUEST_INTERVAL_MS)
        {
            return;
        }

        if (this.lastEntityRequestTimes.size() > MAX_TRACKED_POSITIONS)
        {
            this.lastEntityRequestTimes.clear();
        }

        this.lastEntityRequestTimes.put(entity.getEntityId(), now);

        UUID id = UUID.randomUUID();
        BlockPos origin = entity.getPosition().toImmutable();
        this.pending.put(id, new Pending(origin, now, true));
        this.sendRequest(id, origin, true, handler);
    }

    /**
     * Counts the requests that were not answered in time as failures.
     * ponytail: the server does not announce that it supports litematica:save (the rule may be off, or it may not
     * be Carpet at all), so this probes and gives up after 3 timeouts until the next world change.
     * A capability handshake would be the proper fix if the protocol ever gets one.
     */
    protected void expirePending(long now)
    {
        for (Iterator<Pending> it = this.pending.values().iterator(); it.hasNext(); )
        {
            if (now - it.next().sentTime > REQUEST_TIMEOUT_MS)
            {
                it.remove();

                if (++this.failures == MAX_FAILURES)
                {
                    Litematica.logger.info("Server container preview: no response from the server to {} requests, giving up",
                                           MAX_FAILURES);
                }
            }
        }
    }

    protected void sendRequest(UUID id, BlockPos origin, boolean entities, NetHandlerPlayClient handler)
    {
        // Same format as SchematicSavePacketHandler#sendSaveRequestPacket
        NBTTagCompound dataTypesTag = new NBTTagCompound();
        NbtWrap.putBoolean(dataTypesTag, "Blocks", false);
        NbtWrap.putBoolean(dataTypesTag, "BlockEntities", entities == false);
        NbtWrap.putBoolean(dataTypesTag, "BlockTicks", false);
        NbtWrap.putBoolean(dataTypesTag, "Entities", entities);
        NbtWrap.putBoolean(dataTypesTag, "ExposedBlocksOnly", false);

        NBTTagCompound regionsTag = new NBTTagCompound();
        // An entity can stand a block above or below the block of its position, so the region is a 3x3x3 cube
        final int dy = entities ? 1 : 0;
        NbtWrap.putIntArray(regionsTag, "container", new int[] {
                origin.getX() - 1, origin.getY() - dy, origin.getZ() - 1,
                origin.getX() + 1, origin.getY() + dy, origin.getZ() + 1 });

        NBTTagCompound tag = new NBTTagCompound();
        NbtUtils.writeUUID(tag, id);
        NbtWrap.putString(tag, "SaveMethod", "AllAtOnce");
        NbtWrap.putTag(tag, "RequestedData", dataTypesTag);
        NbtWrap.putTag(tag, "Regions", regionsTag);

        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeCompoundTag(tag);
        PacketSplitter.send(SchematicSavePacketHandler.INSTANCE.channelId, buf, handler);
    }

    /**
     * Called for every litematica:save response.
     * @return true if the response belongs to one of the container requests, false if it is not ours
     */
    public boolean onResponse(UUID id, NBTTagCompound tag)
    {
        Pending request = this.pending.remove(id);

        if (request == null)
        {
            return false;
        }

        this.failures = 0;

        if (request.entities)
        {
            List<NBTTagCompound> entityTags = parseEntityResponse(tag);

            if (entityTags.isEmpty() == false)
            {
                Minecraft mc = GameUtils.getClient();
                mc.addScheduledTask(() -> applyEntitiesToWorld(mc.world, request.origin, entityTags));
            }

            return true;
        }

        List<Pair<BlockPos, NBTTagCompound>> blockEntities = parseResponse(request.origin, tag);

        if (blockEntities.isEmpty() == false)
        {
            Minecraft mc = GameUtils.getClient();
            mc.addScheduledTask(() -> applyToWorld(mc.world, blockEntities));
        }

        return true;
    }

    /**
     * Gets the tile entity compounds out of a response to the request made for the given origin,
     * with the x, y and z rewritten from the coordinates relative to the corner of the requested region
     * to world coordinates. Entries outside of the requested region are dropped.
     */
    public static List<Pair<BlockPos, NBTTagCompound>> parseResponse(BlockPos origin, NBTTagCompound response)
    {
        List<Pair<BlockPos, NBTTagCompound>> list = new ArrayList<>();
        NBTTagCompound region = NbtWrap.getCompound(NbtWrap.getCompound(response, "Regions"), "container");
        NBTTagList beList = NbtWrap.getListOfCompounds(region, "BlockEntities");
        final int minX = origin.getX() - 1;
        final int minY = origin.getY();
        final int minZ = origin.getZ() - 1;

        for (int i = 0; i < NbtWrap.getListSize(beList); ++i)
        {
            NBTTagCompound be = NbtWrap.getCompoundAt(beList, i);

            if (NbtWrap.containsInt(be, "x") == false ||
                NbtWrap.containsInt(be, "y") == false ||
                NbtWrap.containsInt(be, "z") == false)
            {
                continue;
            }

            int relX = NbtWrap.getInt(be, "x");
            int relY = NbtWrap.getInt(be, "y");
            int relZ = NbtWrap.getInt(be, "z");

            if (relX < 0 || relX >= REGION_SIZE_X || relY != 0 || relZ < 0 || relZ >= REGION_SIZE_Z)
            {
                continue;
            }

            BlockPos worldPos = new BlockPos(minX + relX, minY + relY, minZ + relZ);
            NBTTagCompound copy = be.copy();
            NbtWrap.putInt(copy, "x", worldPos.getX());
            NbtWrap.putInt(copy, "y", worldPos.getY());
            NbtWrap.putInt(copy, "z", worldPos.getZ());
            list.add(Pair.of(worldPos, copy));
        }

        return list;
    }

    /** Gets the entity compounds out of a response to an entity request. */
    public static List<NBTTagCompound> parseEntityResponse(NBTTagCompound response)
    {
        List<NBTTagCompound> list = new ArrayList<>();
        NBTTagCompound region = NbtWrap.getCompound(NbtWrap.getCompound(response, "Regions"), "container");
        NBTTagList entityList = NbtWrap.getListOfCompounds(region, "Entities");

        for (int i = 0; i < NbtWrap.getListSize(entityList); ++i)
        {
            list.add(NbtWrap.getCompoundAt(entityList, i));
        }

        return list;
    }

    /** Puts the items from the server into the client entities that have an inventory, matched by UUID. */
    protected static void applyEntitiesToWorld(@Nullable WorldClient world, BlockPos origin, List<NBTTagCompound> entityTags)
    {
        if (world == null)
        {
            return;
        }

        AxisAlignedBB box = new AxisAlignedBB(origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
                                              origin.getX() + 2, origin.getY() + 2, origin.getZ() + 2);

        for (Entity entity : world.getEntitiesWithinAABB(Entity.class, box))
        {
            if ((entity instanceof IInventory) == false)
            {
                continue;
            }

            UUID uuid = entity.getUniqueID();

            for (NBTTagCompound tag : entityTags)
            {
                if (new UUID(NbtWrap.getLong(tag, "UUIDMost"), NbtWrap.getLong(tag, "UUIDLeast")).equals(uuid))
                {
                    fillInventory((IInventory) entity, tag);
                    break;
                }
            }
        }
    }

    protected static void fillInventory(IInventory inventory, NBTTagCompound tag)
    {
        NBTTagList items = NbtWrap.getListOfCompounds(tag, "Items");

        inventory.clear();

        for (int i = 0; i < NbtWrap.getListSize(items); ++i)
        {
            NBTTagCompound item = NbtWrap.getCompoundAt(items, i);
            int slot = item.getByte("Slot") & 255;

            if (slot < inventory.getSizeInventory())
            {
                inventory.setInventorySlotContents(slot, new ItemStack(item));
            }
        }
    }

    protected static void applyToWorld(@Nullable WorldClient world, List<Pair<BlockPos, NBTTagCompound>> blockEntities)
    {
        if (world == null)
        {
            return;
        }

        for (Pair<BlockPos, NBTTagCompound> pair : blockEntities)
        {
            TileEntity te = world.getTileEntity(pair.getLeft());

            if (te instanceof IInventory)
            {
                NBTTagCompound tag = pair.getRight();
                ResourceLocation key = TileEntity.getKey(te.getClass());

                if (key != null && key.toString().equals(NbtWrap.getString(tag, "id")))
                {
                    te.readFromNBT(tag);
                }
            }
        }
    }
}
