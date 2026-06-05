package com.incidentplatform.infrastructure.search.repository;

import com.incidentplatform.infrastructure.search.document.LogDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LogElasticsearchRepository — Spring Data Elasticsearch operations for service logs.
 */
@Repository
public interface LogElasticsearchRepository extends ElasticsearchRepository<LogDocument, String> {
    
    /**
     * Finds logs for a specific service generated after a certain timestamp.
     */
    List<LogDocument> findByServiceNameAndTimestampAfter(String serviceName, LocalDateTime after);

    @org.springframework.data.elasticsearch.annotations.Query(
        "{\"bool\": {\"must\": [{\"term\": {\"serviceName\": \"?0\"}}, {\"match\": {\"message\": \"?1\"}}]}}"
    )
    List<LogDocument> searchLogs(String serviceName, String messageQuery);
}
