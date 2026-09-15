package com.clougence.clouddm.console.web.global.jwtsession;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;

/**
 * Attention!!! The annotation only support method that return ResponseData type
 *
 * @author mode create time is 2020/4/16
 **/
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestAuth {

    // Contains any one of them,RoleAuthLabelName
    String[] value() default {};

    AuthStrategy strategy() default AuthStrategy.RefRoleSet;

    SecurityLevel level() default SecurityLevel.NORMAL;

    ResourceType resType() default ResourceType.PURE_URL;

    boolean checkOpPassword() default false;

    String failedRedirectUrlTo() default "";

    enum AuthStrategy {
        // Ignore check
        Ignore,
        RefAnyOnes,
        // match any UserRoleType on user.
        RefRoleSet,
        /**
         * Primary-account or maintainer only.
         * <p>
         * Enforced by {@link JwtManager#verifyAuth}: {@code isMaintainer() || PRIMARY_ACCOUNT}
         * short-circuits to true before {@code testAuth} is reached, so this strategy
         * only applies to sub-accounts — and for them {@code testAuth} returns false,
         * yielding a 406. This replaces the old pattern of putting a data-auth label
         * (e.g. DM_DAUTH_TICKET, which is a SecDataAuthLabel and never appears in any
         * role's roleAuthLabels) into {@code value()}, which produced the same 406 for
         * every sub-account but with a misleading "missing auth label" error message.
         */
        RefPrimaryOrMaintainer
    }
}
