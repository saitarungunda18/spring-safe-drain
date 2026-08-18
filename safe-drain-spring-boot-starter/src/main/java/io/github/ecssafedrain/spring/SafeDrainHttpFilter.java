package io.github.ecssafedrain.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecssafedrain.core.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Tracks configured Servlet requests and rejects them after drain admission closes. */
public final class SafeDrainHttpFilter extends OncePerRequestFilter {
  private final InFlightWorkRegistry registry;
  private final DrainCoordinator coordinator;
  private final SafeDrainProperties.Http properties;
  private final ObjectMapper mapper;
  private final AntPathMatcher matcher = new AntPathMatcher();

  public SafeDrainHttpFilter(
      InFlightWorkRegistry r, DrainCoordinator c, SafeDrainProperties.Http p, ObjectMapper m) {
    registry = r;
    coordinator = c;
    properties = p;
    mapper = m;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    // Explicit exclusions win; unrelated paths are left completely untouched.
    return properties.getExcludedPaths().stream().anyMatch(p -> matcher.match(p, path))
        || properties.getProtectedPaths().stream().noneMatch(p -> matcher.match(p, path));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    Optional<WorkToken> acquired = registry.tryBegin("http");
    if (acquired.isEmpty()) {
      reject(res);
      return;
    }
    WorkToken token = acquired.get();
    boolean async = false;
    try {
      // The controller executes while this request owns its registry token.
      chain.doFilter(req, res);
      if (req.isAsyncStarted()) {
        // Async Servlet work outlives this thread, so its listener owns token completion.
        req.getAsyncContext().addListener(new ClosingListener(token));
        async = true;
      }
    } finally {
      if (!async) token.close();
    }
  }

  private void reject(HttpServletResponse r) throws IOException {
    // Do not enter the controller after admission has closed.
    r.setStatus(503);
    r.setContentType("application/json");
    r.setHeader("Retry-After", Integer.toString(properties.getRetryAfterSeconds()));
    mapper.writeValue(
        r.getOutputStream(),
        Map.of(
            "code",
            "SERVICE_DRAINING",
            "message",
            "This service is temporarily not accepting new work.",
            "state",
            coordinator.status().state().name()));
  }

  /** Closes an asynchronous request token on every terminal Servlet callback. */
  private static final class ClosingListener implements AsyncListener {
    private final WorkToken token;

    ClosingListener(WorkToken t) {
      token = t;
    }

    public void onComplete(AsyncEvent e) {
      token.close();
    }

    public void onTimeout(AsyncEvent e) {
      token.close();
    }

    public void onError(AsyncEvent e) {
      token.close();
    }

    public void onStartAsync(AsyncEvent e) {
      e.getAsyncContext().addListener(this);
    }
  }
}
