package io.github.ecssafedrain.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecssafedrain.core.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** Creates Safe Drain infrastructure when an application explicitly enables the framework. */
@AutoConfiguration
@EnableConfigurationProperties(SafeDrainProperties.class)
@ConditionalOnProperty(prefix = "safe-drain", name = "enabled", havingValue = "true")
public class SafeDrainAutoConfiguration {
  /** Supplies the default process-local admission registry. */
  @Bean
  @ConditionalOnMissingBean
  public InFlightWorkRegistry inFlightWorkRegistry() {
    return new InFlightWorkRegistry();
  }

  /** Supplies the lifecycle coordinator and discovers optional participant beans. */
  @Bean
  @ConditionalOnMissingBean
  public DrainCoordinator drainCoordinator(
      InFlightWorkRegistry r, ObjectProvider<DrainParticipant> p, SafeDrainProperties cfg) {
    return new DrainCoordinator(r, p.orderedStream().toList(), cfg.getTimeout());
  }

  /** Exposes the management endpoint through Spring Boot Actuator. */
  @Bean
  @ConditionalOnMissingBean
  public SafeDrainEndpoint safeDrainEndpoint(DrainCoordinator c) {
    return new SafeDrainEndpoint(c);
  }

  /** Registers the Servlet filter ahead of application request handling. */
  @Bean
  @ConditionalOnMissingBean(name = "safeDrainHttpFilterRegistration")
  @ConditionalOnProperty(
      prefix = "safe-drain.http",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public FilterRegistrationBean<SafeDrainHttpFilter> safeDrainHttpFilterRegistration(
      InFlightWorkRegistry r, DrainCoordinator c, SafeDrainProperties p, ObjectMapper m) {
    var b = new FilterRegistrationBean<>(new SafeDrainHttpFilter(r, c, p.getHttp(), m));
    b.setName("safeDrainHttpFilter");
    b.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    return b;
  }
}
