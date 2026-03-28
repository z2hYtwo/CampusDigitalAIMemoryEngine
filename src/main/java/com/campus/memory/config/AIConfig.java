package com.campus.memory.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AIConfig {

    @Value("${langchain4j.open-ai.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.embedding-model.model-name}")
    private String embeddingModelName;

    @Value("${langchain4j.embedding.api-key:${langchain4j.open-ai.api-key}}")
    private String embeddingApiKey;

    @Value("${langchain4j.embedding.base-url:${langchain4j.open-ai.base-url}}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.embedding.model-name:${langchain4j.open-ai.embedding-model.model-name}}")
    private String configuredEmbeddingModelName;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String chatModelName;

    @Value("${langchain4j.open-ai.chat-model.temperature}")
    private Double temperature;

    @Value("${langchain4j.chat.api-key:${langchain4j.open-ai.api-key}}")
    private String chatApiKey;

    @Value("${langchain4j.chat.base-url:${langchain4j.open-ai.base-url}}")
    private String chatBaseUrl;

    @Value("${langchain4j.chat.model-name:${langchain4j.open-ai.chat-model.model-name}}")
    private String configuredChatModelName;

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(embeddingApiKey)
                .baseUrl(embeddingBaseUrl)
                .modelName(configuredEmbeddingModelName != null && !configuredEmbeddingModelName.isBlank() ? configuredEmbeddingModelName : embeddingModelName)
                .timeout(Duration.ofSeconds(60)) // 增加超时到 60s
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(chatApiKey != null && !chatApiKey.isBlank() ? chatApiKey : apiKey)
                .baseUrl(chatBaseUrl != null && !chatBaseUrl.isBlank() ? chatBaseUrl : baseUrl)
                .modelName(configuredChatModelName != null && !configuredChatModelName.isBlank() ? configuredChatModelName : chatModelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(60)) // 增加超时到 60s
                .maxRetries(3) // 增加重试次数
                .logRequests(true)
                .logResponses(true)
                .build();
    }

}
