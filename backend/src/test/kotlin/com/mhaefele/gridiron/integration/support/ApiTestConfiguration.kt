package com.mhaefele.gridiron.integration.support

import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import org.springframework.web.client.RestTemplate
import tools.jackson.databind.json.JsonMapper

@TestConfiguration
class ApiTestConfiguration {

    /**
     * [LocalServerPort] is assigned only after Tomcat binds the random test port.
     * `@Lazy` defers these beans until first injection so creation runs after the server is up —
     * otherwise the port can be `0` during early context refresh.
     */
    @Bean
    @Lazy
    fun restTemplate(
        builder: RestTemplateBuilder,
        @LocalServerPort port: Int,
    ): RestTemplate = builder.rootUri("http://localhost:$port").build()

    @Bean
    @Lazy
    fun gridironApiClient(
        restTemplate: RestTemplate,
        jsonMapper: JsonMapper,
    ): GridironApiClient = GridironApiClient(restTemplate, jsonMapper)

    @Bean
    @Lazy
    fun sseTestClient(
        @LocalServerPort port: Int,
        jsonMapper: JsonMapper,
    ): SseTestClient = SseTestClient("http://localhost:$port", jsonMapper)
}
