import com.intellij.openapi.components.Service;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

<warning descr="Application level service requires no-arg constructor or constructor taking Coroutine">@Service(Service.Level.APP<caret>)</warning>
final class MyService {
  private <warning descr="If constructor takes Project, Service.Level.PROJECT is required">MyService</warning>(Project project) {}
}
