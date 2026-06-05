package com.incidentplatform.application.service;

import com.incidentplatform.infrastructure.persistence.entity.KnowledgeDocumentEntity;
import com.incidentplatform.infrastructure.persistence.repository.KnowledgeDocumentRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * DocumentIngestionService — Implements runbook document chunking, embedding, and vector indexing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class DocumentIngestionService {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    /**
     * Ingests a new runbook document:
     * 1. Persists the metadata and text in PostgreSQL.
     * 2. Chunks the runbook text into sliding window segments.
     * 3. Generates vector embeddings for each chunk and indexes them in pgvector.
     *
     * @param title SRE runbook title
     * @param content Full text content
     * @param serviceName Service associated with the runbook
     * @param tags Categorization tags
     * @param createdBy Author / system user
     * @return Saved database entity
     */
    public KnowledgeDocumentEntity ingestDocument(String title, String content, String serviceName, List<String> tags, String createdBy) {
        log.info("Ingesting knowledge document: title='{}', serviceName='{}'", title, serviceName);

        // 1. Persist the source document in standard relational PostgreSQL table
        KnowledgeDocumentEntity doc = KnowledgeDocumentEntity.builder()
                .title(title)
                .content(content)
                .serviceName(serviceName)
                .tags(tags)
                .createdBy(createdBy != null ? createdBy : "SYSTEM")
                .documentType("RUNBOOK")
                .build();
        KnowledgeDocumentEntity savedDoc = knowledgeDocumentRepository.save(doc);

        // 2. Perform text chunking (sliding character window)
        // 300 character chunks, 30 character overlap to avoid splitting keywords/context across bounds
        List<String> chunks = chunkText(content, 300, 30);
        log.debug("Split runbook '{}' into {} chunks for vector indexing.", title, chunks.size());

        // 3. Generate embeddings and save to pgvector table (knowledge_embeddings)
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            
            // Populate metadata so that semantic matching can trace back to this specific document
            Metadata metadata = new Metadata();
            metadata.add("documentId", savedDoc.getId().toString());
            metadata.add("title", title);
            metadata.add("serviceName", serviceName);
            metadata.add("chunkIndex", i);

            TextSegment segment = TextSegment.from(chunkText, metadata);
            try {
                var embeddingResponse = embeddingModel.embed(segment);
                embeddingStore.add(embeddingResponse.content(), segment);
            } catch (Exception e) {
                log.error("Failed to save vector embedding for chunk {} of document '{}': {}", i, title, e.getMessage());
                // The failsafe model returns a mock vector if Ollama is offline, so this should not fail.
            }
        }

        return savedDoc;
    }

    /**
     * Sliding window text chunking logic.
     */
    private List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
            start += (chunkSize - overlap);
        }
        return chunks;
    }
}
