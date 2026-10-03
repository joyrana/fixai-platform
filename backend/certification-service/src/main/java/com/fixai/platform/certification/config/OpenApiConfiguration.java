package com.fixai.platform.certification.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI certificationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("FIXAI Certification API")
                .version("v1")
                .description("Deterministic FIX certification: scenarios, runs, evidence, replay verification and reports. "
                        + "Verdicts are computed only from executable assertions."));
    }
}
