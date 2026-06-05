package com.incidentplatform.infrastructure.search.repository;

import com.incidentplatform.infrastructure.search.document.IncidentDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * IncidentElasticsearchRepository — Spring Data Elasticsearch operations for incidents.
 */
@Repository
public interface IncidentElasticsearchRepository extends ElasticsearchRepository<IncidentDocument, String> {
    
    /**
     * Performs full-text search across multiple incident text fields.
     */
    @org.springframework.data.elasticsearch.annotations.Query(
        "{\"multi_match\": {\"query\": \"?0\", \"fields\": [\"title\", \"description\", \"rcaSummary\"]}}"
    )
    List<IncidentDocument> search(String query);
}
