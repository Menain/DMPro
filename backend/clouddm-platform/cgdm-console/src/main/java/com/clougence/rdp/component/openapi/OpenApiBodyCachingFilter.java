/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.rdp.component.openapi;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Wraps every {@code /api/open/*} request with {@link CachedBodyHttpServletRequest} so the
 * body can be read both by {@link OpenApiSessionManager} (body-digest verification) and
 * by the controller's {@code @RequestBody} binding.
 *
 * <p>Registered as a Servlet Filter with the highest precedence so it runs before any
 * interceptor (interceptors always execute after filters in the Spring request pipeline).</p>
 */
public class OpenApiBodyCachingFilter implements Filter {

    private final String openApiUriPrefix;

    public OpenApiBodyCachingFilter(String openApiUriPrefix) {
        this.openApiUriPrefix = openApiUriPrefix;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest) {
            String uri = httpRequest.getRequestURI();
            if (uri != null && uri.startsWith(this.openApiUriPrefix)) {
                chain.doFilter(new CachedBodyHttpServletRequest(httpRequest), response);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
