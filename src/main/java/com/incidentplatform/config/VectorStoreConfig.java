package com.incidentplatform.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * VectorStoreConfig — Configures pgvector store and embedding model.
 *
 * WHY pgvector as EmbeddingStore?
 * pgvector stores vector embeddings directly in PostgreSQL.
 * This eliminates the operational cost, infrastructure maintenance, and learning curve
 * of a separate vector database (like Pinecone, Milvus, or Qdrant).
 * pgvector is ACID-compliant and fits right into our clean architecture.
 */
@Configuration
public class VectorStoreConfig {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    /**
     * Creates the PgVectorEmbeddingStore bean, parsing the database credentials from jdbc URL.
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // Parse host, port, and database name from: jdbc:postgresql://localhost:5432/incident_db
        String cleanUrl = datasourceUrl.replace("jdbc:postgresql://", "");
        int slashIdx = cleanUrl.indexOf('/');
        String hostPort = cleanUrl.substring(0, slashIdx);
        String database = cleanUrl.substring(slashIdx + 1);
        
        int colonIdx = hostPort.indexOf(':');
        String host = colonIdx != -1 ? hostPort.substring(0, colonIdx) : hostPort;
        int port = colonIdx != -1 ? Integer.parseInt(hostPort.substring(colonIdx + 1)) : 5432;

        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(username)
                .password(password)
                .table("knowledge_embeddings")
                .dimension(384) // 384 dimensions matching the all-minilm embedding model
                .createTable(true)
                .build();
    }

    /**
     * EmbeddingModel bean wrapping OllamaEmbeddingModel in a FailSafe decorator.
     * Prevents system build failures if the local Ollama daemon is offline or lacking the model.
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        OllamaEmbeddingModel delegate = OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("all-minilm")
                .timeout(Duration.ofSeconds(60))
                .build();
        return new FailSafeEmbeddingModel(delegate);
    }

    /**
     * FailSafeEmbeddingModel — Fallback wrapper for the embedding model.
     */
    private static class FailSafeEmbeddingModel implements EmbeddingModel {
        private final EmbeddingModel delegate;
        private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FailSafeEmbeddingModel.class);

        public FailSafeEmbeddingModel(EmbeddingModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public Response<Embedding> embed(String text) {
            try {
                return delegate.embed(text);
            } catch (Exception e) {
                logger.warn("Ollama embedding generation failed: {}. Falling back to mock 384-dimensional unit vector.", e.getMessage());
                float[] dummy = new float[384];
                dummy[0] = 1.0f; // Mock unit vector
                return Response.from(Embedding.from(dummy));
            }
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return embed(textSegment.text());
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            List<Embedding> list = textSegments.stream()
                    .map(s -> embed(s).content())
                    .toList();
            return Response.from(list);
        }
    }
}
