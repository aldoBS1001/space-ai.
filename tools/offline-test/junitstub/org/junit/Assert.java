package org.junit;
import java.util.Objects;
// Stub local mínimo con la misma semántica que JUnit 4 (Maven está bloqueado en este entorno).
public final class Assert {
  public static void assertTrue(boolean c){ if(!c) throw new AssertionError("se esperaba true"); }
  public static void assertTrue(String m, boolean c){ if(!c) throw new AssertionError(m); }
  public static void assertFalse(boolean c){ if(c) throw new AssertionError("se esperaba false"); }
  public static void assertEquals(Object e, Object a){ if(!Objects.equals(e,a)) throw new AssertionError("esperado <"+e+"> pero fue <"+a+">"); }
  public static void assertEquals(long e, long a){ if(e!=a) throw new AssertionError("esperado <"+e+"> pero fue <"+a+">"); }
  public static void assertEquals(float e, float a, float d){ if(Math.abs(e-a)>d) throw new AssertionError("esperado <"+e+"> pero fue <"+a+">"); }
}
