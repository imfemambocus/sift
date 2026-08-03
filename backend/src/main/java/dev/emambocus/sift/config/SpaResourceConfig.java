package dev.emambocus.sift.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/*
 * react router owns every path that is not an api call, so a request for one has to be answered
 * with index.html. without this, opening or refreshing /feed/gitlab directly is a 404.
 */
@Configuration
public class SpaResourceConfig implements WebMvcConfigurer {

	private static final String STATIC_ROOT = "classpath:/static/";
	private static final String INDEX = "/static/index.html";

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/**")
				.addResourceLocations(STATIC_ROOT)
				.resourceChain(true)
				.addResolver(new PathResourceResolver() {

					@Override
					protected Resource getResource(String resourcePath, Resource location) throws IOException {
						Resource requested = location.createRelative(resourcePath);
						if (requested.exists() && requested.isReadable()) {
							return requested;
						}
						// a missing api or actuator path must stay a 404 rather than silently
						// returning the app shell with a 200
						if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
							return null;
						}
						return new ClassPathResource(INDEX);
					}
				});
	}
}
