package litematica.network;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import com.google.common.collect.ImmutableList;
import io.netty.buffer.Unpooled;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.util.ResourceLocation;

import malilib.network.message.BasePacketHandler;
import malilib.util.game.wrap.GameUtils;
import malilib.util.game.wrap.NbtWrap;
import litematica.Litematica;
import litematica.Reference;

/**
 * Client side of the "Litematica Server Paster" protocol (channel "network_v2").
 * It allows sending commands longer than the vanilla 256 character chat limit,
 * for example /setblock commands that include tile entity NBT.
 * <br><br>
 * Every packet is a varint packet id followed by an NBT compound.<br>
 * C2S: HI=0 {mod_version}, CHAT=1 {chat}, VERY_LONG_CHAT_START=2 {},
 * VERY_LONG_CHAT_CONTENT=3 {segment}, VERY_LONG_CHAT_END=4 {}<br>
 * S2C: HI=0 {mod_version}, ACCEPT_PACKETS=1 {ids: int[]}
 */
public class ServerPasterPacketHandler extends BasePacketHandler
{
    public static final ServerPasterPacketHandler INSTANCE = new ServerPasterPacketHandler();

    /** The raw channel name, the server compares it as a plain string */
    protected static final String CHANNEL_NAME = "network_v2";

    protected static final int C2S_HI = 0;
    protected static final int C2S_CHAT = 1;
    protected static final int C2S_VERY_LONG_CHAT_START = 2;
    protected static final int C2S_VERY_LONG_CHAT_CONTENT = 3;
    protected static final int C2S_VERY_LONG_CHAT_END = 4;
    protected static final int S2C_HI = 0;
    protected static final int S2C_ACCEPT_PACKETS = 1;

    /** A custom payload packet can be at most 32767 bytes, and a char takes up to 3 bytes in NBT */
    protected static final int MAX_SEGMENT_LENGTH = 10000;

    protected final List<ResourceLocation> channels = ImmutableList.of(new ResourceLocation(CHANNEL_NAME));
    protected boolean supported;

    @Override
    public List<ResourceLocation> getChannels()
    {
        return this.channels;
    }

    @Override
    public void onPacketReceived(PacketBuffer buf)
    {
        try
        {
            int id = buf.readVarInt();
            NBTTagCompound tag = buf.readCompoundTag();

            if (id == S2C_HI)
            {
                Litematica.logger.info("Server paster: server version {}", tag != null ? NbtWrap.getString(tag, "mod_version") : "?");
            }
            else if (id == S2C_ACCEPT_PACKETS && tag != null)
            {
                Set<Integer> accepted = new HashSet<>();

                for (int acceptedId : NbtWrap.getIntArray(tag, "ids"))
                {
                    accepted.add(acceptedId);
                }

                this.supported = accepted.contains(C2S_CHAT) &&
                                 accepted.contains(C2S_VERY_LONG_CHAT_START) &&
                                 accepted.contains(C2S_VERY_LONG_CHAT_CONTENT) &&
                                 accepted.contains(C2S_VERY_LONG_CHAT_END);
                Litematica.logger.info("Server paster: handshake complete, supported: {}", this.supported);
            }
        }
        catch (Exception e)
        {
            Litematica.logger.warn("Server paster: failed to read a packet from the server", e);
        }
    }

    /**
     * Called after the client world has changed. Starts the handshake with the server.
     * Until the server has answered, the commands are sent as normal chat messages.
     */
    public void onClientWorldChange(@Nullable WorldClient worldAfter)
    {
        this.supported = false;

        if (worldAfter != null && GameUtils.isSinglePlayer() == false)
        {
            NBTTagCompound tag = new NBTTagCompound();
            NbtWrap.putString(tag, "mod_version", Reference.MOD_VERSION);
            this.sendPacket(C2S_HI, tag);
        }
    }

    public boolean isSupported()
    {
        return this.supported;
    }

    /**
     * Sends the command to the server using the paster packets, which allow long commands.
     * If the server did not complete the handshake, the command is sent as a normal chat message.
     */
    public void sendCommand(String command)
    {
        if (this.supported == false)
        {
            GameUtils.sendCommand(command);
        }
        else if (command.length() <= MAX_SEGMENT_LENGTH)
        {
            this.sendString(C2S_CHAT, "chat", command);
        }
        else
        {
            this.sendPacket(C2S_VERY_LONG_CHAT_START, new NBTTagCompound());

            for (int offset = 0; offset < command.length(); offset += MAX_SEGMENT_LENGTH)
            {
                String segment = command.substring(offset, Math.min(offset + MAX_SEGMENT_LENGTH, command.length()));
                this.sendString(C2S_VERY_LONG_CHAT_CONTENT, "segment", segment);
            }

            this.sendPacket(C2S_VERY_LONG_CHAT_END, new NBTTagCompound());
        }
    }

    protected void sendString(int id, String key, String value)
    {
        NBTTagCompound tag = new NBTTagCompound();
        NbtWrap.putString(tag, key, value);
        this.sendPacket(id, tag);
    }

    protected void sendPacket(int id, NBTTagCompound tag)
    {
        NetHandlerPlayClient handler = GameUtils.getClient().getConnection();

        if (handler != null)
        {
            PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
            buf.writeVarInt(id);
            buf.writeCompoundTag(tag);
            handler.sendPacket(new CPacketCustomPayload(CHANNEL_NAME, buf));
        }
    }
}
