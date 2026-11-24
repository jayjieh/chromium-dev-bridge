package dev.cdb.driver.security;

import dev.cdb.core.BridgeException;
import dev.cdb.core.Principal;
import dev.cdb.protocol.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the authenticated {@link Principal} into controller methods. */
public class PrincipalArgumentResolver implements HandlerMethodArgumentResolver {
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(Principal.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        Object p = request.getAttribute(SecurityFilter.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (p instanceof Principal principal) return principal;
        throw new BridgeException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
    }
}
