/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.app.services.worker.KnowledgeClient;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Keeps existing fake-index oracles while adapting the added explicit attempt argument. */
final class VduAttemptTestSupport {
  private VduAttemptTestSupport() {}
  static KnowledgeClient client() { return client(Answers.RETURNS_DEFAULTS); }
  static KnowledgeClient client(Answer<?> otherwise) {
    return Mockito.mock(KnowledgeClient.class, call -> {
      Object[] args=call.getArguments();
      String method=call.getMethod().getName();
      int remove=method.equals("markVduProcessing") && args.length==4 ? 2
          : method.equals("updateVduResult") && args.length==7 ? 5
          : method.equals("releaseVduProcessing") && args.length==3 ? 1 : -1;
      if (remove<0) return otherwise.answer(call);
      Object[] original=new Object[args.length-1];
      System.arraycopy(args,0,original,0,remove);
      System.arraycopy(args,remove+1,original,remove,args.length-remove-1);
      Class<?>[] types=call.getMethod().getParameterTypes();
      Class<?>[] oldTypes=new Class<?>[types.length-1];
      System.arraycopy(types,0,oldTypes,0,remove);
      System.arraycopy(types,remove+1,oldTypes,remove,types.length-remove-1);
      try { return KnowledgeClient.class.getMethod(method,oldTypes).invoke(call.getMock(),original); }
      catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    });
  }
}
