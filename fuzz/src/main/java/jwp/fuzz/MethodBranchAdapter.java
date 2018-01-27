package jwp.fuzz;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.ListIterator;
import java.util.Set;
import java.util.function.Consumer;

public class MethodBranchAdapter extends MethodNode {

  private final MethodRefs refs;
  private final String className;
  private final MethodVisitor mv;

  public MethodBranchAdapter(MethodRefs refs, String className, int access, String name,
      String desc, String signature, String[] exceptions, MethodVisitor mv) {
    super(Opcodes.ASM6, access, name, desc, signature, exceptions);
    this.refs = refs;
    this.className = className;
    this.mv = mv;
  }

  // Make sure index is the index AFTER nodes are inserted
  private int insnHashCode(int index) {
    return Arrays.hashCode(new int[] { className.hashCode(), name.hashCode(), desc.hashCode(), index });
  }

  private void insertBeforeAndInvokeStaticWithHash(AbstractInsnNode insn, MethodRef ref, AbstractInsnNode... before) {
    InsnList insns = new InsnList();
    int insnIndex = instructions.indexOf(insn);
    for (AbstractInsnNode node : before) insns.add(node);
    // Add branch hash and make static call
    insns.add(new LdcInsnNode(insnHashCode(insnIndex + before.length + 2)));
    insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ref.classSig, ref.methodName, ref.methodSig, false));
    instructions.insertBefore(insn, insns);
  }

  @Override
  public void visitEnd() {
    // We need the handler labels for catch clauses
    Set<Label> catchHandlerLabels = new HashSet<>(tryCatchBlocks.size());
    for (TryCatchBlockNode catchBlock : tryCatchBlocks) catchHandlerLabels.add(catchBlock.handler.getLabel());
    // Go over each instruction, injecting static calls where necessary
    ListIterator<AbstractInsnNode> iter = instructions.iterator();
    while (iter.hasNext()) {
      AbstractInsnNode insn = iter.next();
      int op = insn.getOpcode();
      switch (op) {
        case Opcodes.IFEQ:
        case Opcodes.IFNE:
        case Opcodes.IFLT:
        case Opcodes.IFGE:
        case Opcodes.IFGT:
        case Opcodes.IFLE:
          // Needs duped value
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op], new InsnNode(Opcodes.DUP));
          break;
        case Opcodes.IF_ICMPEQ:
        case Opcodes.IF_ICMPNE:
        case Opcodes.IF_ICMPLT:
        case Opcodes.IF_ICMPGE:
        case Opcodes.IF_ICMPGT:
        case Opcodes.IF_ICMPLE:
          // Needs duped values
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op], new InsnNode(Opcodes.DUP2));
          break;
        case Opcodes.IF_ACMPEQ:
        case Opcodes.IF_ACMPNE:
          // Needs duped values
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op], new InsnNode(Opcodes.DUP2));
          break;
        case Opcodes.IFNULL:
        case Opcodes.IFNONNULL:
          // Needs duped value
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op], new InsnNode(Opcodes.DUP));
          break;
        case Opcodes.TABLESWITCH:
          TableSwitchInsnNode tableInsn = (TableSwitchInsnNode) insn;
          // Needs duped value and the min and max consts
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op],
              new InsnNode(Opcodes.DUP), new LdcInsnNode(tableInsn.min), new LdcInsnNode(tableInsn.max));
          break;
        case Opcodes.LOOKUPSWITCH:
          // Needs duped value and an array of all the jump keys
          // XXX: should we really be creating this array here on every lookup? We could just assume this is always
          // a branch and hash off the value. We could also have our own lookup switch but it doesn't give much. We
          // could also put this array as a synthetic field on the class.
          LookupSwitchInsnNode lookupSwitch = (LookupSwitchInsnNode) insn;
          AbstractInsnNode[] nodes = new AbstractInsnNode[(4 * lookupSwitch.keys.size()) + 3];
          nodes[0] = new InsnNode(Opcodes.DUP);
          nodes[1] = new LdcInsnNode(lookupSwitch.keys.size());
          nodes[2] = new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT);
          for (int i = 0; i < lookupSwitch.keys.size(); i++) {
            nodes[(i * 4) + 3] = new InsnNode(Opcodes.DUP);
            nodes[(i * 4) + 4] = new LdcInsnNode(i);
            nodes[(i * 4) + 5] = new LdcInsnNode(lookupSwitch.keys.get(i));
            nodes[(i * 4) + 6] = new InsnNode(Opcodes.IASTORE);
          }
          insertBeforeAndInvokeStaticWithHash(insn, refs.refsByOpcode[op], nodes);
          break;
        case -1:
          // TODO: Do non-Java langs handle this differently?
          // If this is a handler label, go to the next non-line-num and non-frame insn and insert our stuff
          // before that.
          if (insn instanceof LabelNode && catchHandlerLabels.contains(((LabelNode) insn).getLabel())) {
            AbstractInsnNode next = insn.getNext();
            while (next instanceof LineNumberNode || next instanceof FrameNode) { next = next.getNext(); }
            // Dupe the exception and call
            insertBeforeAndInvokeStaticWithHash(next, refs.refsByOpcode[Opcodes.ATHROW], new InsnNode(Opcodes.DUP));
          }
          break;
      }
    }
    accept(mv);
  }

  public static class MethodRefs {

    public static Builder builder() { return new Builder(); }

    private final MethodRef[] refsByOpcode;

    private MethodRefs(MethodRef[] refsByOpcode) { this.refsByOpcode = refsByOpcode; }

    public static class Builder {
      private static final Type OBJECT_TYPE = Type.getType(Object.class);
      private static final Type INT_ARRAY_TYPE = Type.getType(int[].class);
      private static final Type THROWABLE_TYPE = Type.getType(Throwable.class);

      @SuppressWarnings("unchecked")
      private final static Consumer<MethodRef>[] validityCheckers = new Consumer[Opcodes.IFNONNULL + 1];

      static {
        // void check(int value, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE),
            Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE);
        // void check(int lvalue, int rvalue, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE),
            Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
            Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE);
        // void check(Object lvalue, Object rvalue, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, OBJECT_TYPE, OBJECT_TYPE, Type.INT_TYPE),
            Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE);
        // void check(Object value, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, OBJECT_TYPE, Type.INT_TYPE),
            Opcodes.IFNULL, Opcodes.IFNONNULL);
        // void check(int value, int min, int max, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE),
            Opcodes.TABLESWITCH);
        // void check(int value, int[] keys, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, Type.INT_TYPE, INT_ARRAY_TYPE, Type.INT_TYPE),
            Opcodes.LOOKUPSWITCH);
        // void check(Throwable value, int branchHash)
        addChecks(m -> m.assertType(Type.VOID_TYPE, THROWABLE_TYPE, Type.INT_TYPE),
            Opcodes.ATHROW);
      }

      private static void addChecks(Consumer<MethodRef> check, int... opcodes) {
        for (int opcode : opcodes) validityCheckers[opcode] = check;
      }

      private final MethodRef[] refsByOpcode = new MethodRef[validityCheckers.length];

      // Note: ATHROW is used for catches
      public void set(int opcode, MethodRef ref) { refsByOpcode[opcode] = ref; }

      public MethodRefs build() {
        // Do validity checks
        for (int i = 0; i < validityCheckers.length; i++) {
          Consumer<MethodRef> check = validityCheckers[i];
          MethodRef ref = refsByOpcode[i];
          if (ref != null && check == null) throw new RuntimeException("Expecting no ref for opcode " + i);
          if (ref == null && check != null) throw new RuntimeException("Expecting ref for opcode " + i);
          if (check != null) check.accept(ref);
        }
        return new MethodRefs(refsByOpcode);
      }
    }
  }

  public static class MethodRef {
    public final String classSig;
    public final String methodName;
    public final String methodSig;

    public MethodRef(String classSig, String methodName, String methodSig) {
      this.classSig = classSig;
      this.methodName = methodName;
      this.methodSig = methodSig;
    }

    public MethodRef(Method method) {
      this(Type.getInternalName(method.getDeclaringClass()), method.getName(), Type.getMethodDescriptor(method));
    }

    public void assertType(Type returnType, Type... paramTypes) {
      Type actualReturnType = Type.getReturnType(methodSig);
      if (!returnType.equals(actualReturnType))
        throw new IllegalArgumentException("Invalid return type, expected " + returnType + ", got " + actualReturnType);
      Type[] actualParamTypes = Type.getArgumentTypes(methodSig);
      if (!Arrays.equals(paramTypes, actualParamTypes))
        throw new IllegalArgumentException("Invalid arg types, expected " + Arrays.toString(paramTypes) +
            ", got " + Arrays.toString(actualParamTypes));
    }
  }
}
