package ai.operativus.agentmanager.control.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tool method (typically Spring AI {@code @Tool}) with a capability string used
 * to derive the tool's {@link ai.operativus.agentmanager.core.model.ToolCategory} for
 * the {@code GET /api/tools} listing read by the UI (see
 * {@link ai.operativus.agentmanager.control.controller.ToolController#buildToolCategoryMap}).
 *
 * <p><b>Metadata only.</b> No aspect, interceptor, or runtime gate enforces the
 * capability — placing this annotation on a method does NOT prevent invocation. If a
 * tool needs runtime authorization, layer it via {@code @PreAuthorize}, a dedicated
 * {@code HitlAdvisor} tier, or an explicit check inside the method body.
 *
 * <p>Pinned by {@code ToolControllerRuntimeTest} (the category-resolution contract). If
 * runtime enforcement is added later, update both this Javadoc and the pin.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresCapability {
    String value();
}
