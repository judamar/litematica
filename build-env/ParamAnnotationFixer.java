import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Writes a tiny helper jar with the classes of the input jar that javac refuses to read:
 * constructors of non-static member classes whose parameter annotation attributes have one
 * entry per descriptor parameter, including the outer instance. javac strips the outer instance
 * parameter and fails with "bad RuntimeInvisibleParameterAnnotations attribute".
 * In the fixed copy those attributes are dropped, which is irrelevant for compiling.
 *
 * usage: ParamAnnotationFixer <input jar> <output helper jar>
 */
public class ParamAnnotationFixer
{
    public static void main(String[] args) throws Exception
    {
        int fixed = 0;

        try (JarFile in = new JarFile(new File(args[0]));
             JarOutputStream out = new JarOutputStream(new FileOutputStream(new File(args[1]))))
        {
            for (Enumeration<JarEntry> entries = in.entries(); entries.hasMoreElements();)
            {
                JarEntry entry = entries.nextElement();

                // Only member classes can have an outer instance parameter
                if (entry.getName().endsWith(".class") == false || entry.getName().indexOf('$') < 0)
                {
                    continue;
                }

                ClassReader reader = new ClassReader(readAll(in.getInputStream(entry)));
                ClassWriter writer = new ClassWriter(0);
                Fixer fixer = new Fixer(writer);
                reader.accept(fixer, 0);

                if (fixer.changed)
                {
                    out.putNextEntry(new JarEntry(entry.getName()));
                    out.write(writer.toByteArray());
                    out.closeEntry();
                    System.out.println("fixed " + entry.getName());
                    ++fixed;
                }
            }
        }

        System.out.println("classes fixed: " + fixed);
    }

    private static byte[] readAll(InputStream is) throws Exception
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;

        while ((n = is.read(buf)) > 0)
        {
            bos.write(buf, 0, n);
        }

        is.close();
        return bos.toByteArray();
    }

    private static class Fixer extends ClassVisitor
    {
        boolean changed;
        private String thisName;
        private boolean hasOuterInstance;

        Fixer(ClassVisitor cv)
        {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces)
        {
            this.thisName = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access)
        {
            if (name.equals(this.thisName) && outerName != null && (access & Opcodes.ACC_STATIC) == 0)
            {
                this.hasOuterInstance = true;
            }

            super.visitInnerClass(name, outerName, innerName, access);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions)
        {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

            if (this.hasOuterInstance == false || name.equals("<init>") == false)
            {
                return mv;
            }

            final int paramCount = Type.getArgumentTypes(desc).length;

            return new MethodVisitor(Opcodes.ASM9, mv)
            {
                private boolean dropVisible;
                private boolean dropInvisible;

                @Override
                public void visitAnnotableParameterCount(int count, boolean visible)
                {
                    // javac expects one entry less than the descriptor has, as it strips the outer instance
                    if (count == paramCount)
                    {
                        if (visible) { this.dropVisible = true; } else { this.dropInvisible = true; }
                        Fixer.this.changed = true;
                    }
                    else
                    {
                        super.visitAnnotableParameterCount(count, visible);
                    }
                }

                @Override
                public org.objectweb.asm.AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible)
                {
                    if (visible ? this.dropVisible : this.dropInvisible)
                    {
                        return null;
                    }

                    return super.visitParameterAnnotation(parameter, descriptor, visible);
                }
            };
        }
    }
}
