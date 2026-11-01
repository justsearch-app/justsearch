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
  static VduProcessor processor() {
    return Mockito.mock(VduProcessor.class, call -> {
      if (call.getMethod().getName().equals("process") && call.getArguments().length == 3) {
        return ((VduProcessor) call.getMock()).process(call.getArgument(0), call.getArgument(1));
      }
      return Answers.RETURNS_DEFAULTS.answer(call);
    });
  }
  static KnowledgeClient client() { return client(Answers.RETURNS_DEFAULTS); }
  static KnowledgeClient client(Answer<?> otherwise) {
    return Mockito.mock(KnowledgeClient.class, call -> {
      Object[] args=call.getArguments();
      String method=call.getMethod().getName();
      if (method.equals("updateVduResult") && args.length == 9) {
        return ((KnowledgeClient) call.getMock()).updateVduResult(call.getArgument(0), call.getArgument(1),
            call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(8));
      }
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
