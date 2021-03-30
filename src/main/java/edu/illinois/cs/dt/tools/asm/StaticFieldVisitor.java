package edu.illinois.cs.dt.tools.asm;


public class StaticFieldVisitor extends ClassVisitor {

    private final StaticFieldsCountManager manager;
    private String className;
    private String superClassName;
    private int staticFieldCount;

    public StaticFieldVisitor(StaticFieldsCountManager manager) {
        super(Opcodes.ASM5);
        this.manager = manager;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        // Just capture the meta-data.
        this.superClassName = superName;
        this.className = name;

        // System.out.println("ClassName: " + name);
        // System.out.println("SuperClassName: " + superName);
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        // If this is an instance field, add it to our count.  We don't restrict static fields.
        if (Opcodes.ACC_STATIC == (Opcodes.ACC_STATIC & access) && Opcodes.ACC_FINAL != (Opcodes.ACC_FINAL & access)) {
//            System.out.println("Field: " + name+" , value: " + value);
            this.staticFieldCount += 1;
        }
        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public void visitEnd() {
        // Just report back.
        this.manager.addCount(this.className, this.superClassName, this.staticFieldCount);
//        System.out.println(this.manager.nameToDeclaredCount.toString());
        super.visitEnd();
    }
}
