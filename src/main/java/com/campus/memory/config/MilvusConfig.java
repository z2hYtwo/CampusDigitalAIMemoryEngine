package com.campus.memory.config;

import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {

    @Value("${milvus.host}")
    private String host;

    @Value("${milvus.port}")
    private int port;

    @Value("campus_memory_v4")
    private String collectionName;

    @Value("${milvus.dimension:1024}")
    private int dimension;

    /**
     * 配置 LangChain4j 的 Milvus 向量存储
     */
    @Bean
    public MilvusEmbeddingStore milvusEmbeddingStore() {
        return MilvusEmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collectionName)
                .dimension(dimension)
                .build();
    }
}
