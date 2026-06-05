package com.incidentplatform.infrastructure.search.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;

/**
 * IncidentDocument — Elasticsearch index document mapping for incidents.
 *
 * WHY index incidents in Elasticsearch?
 * Relational databases are optimized for transaction processing and strict joins.
 * They are not designed for full-text search across large textual columns (like rca_summary).
 * Elasticsearch uses an inverted index, enabling sub-second keyword and phrase searches
 * across millions of incidents.
 */
@Document(indexName = "incidents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentDocument {

    @Id
    private String id; // Matches incident UUID

    @Field(type = FieldType.Text, analyzer = "standard")
    private String title;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String description;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Keyword)
    private String severity;

    @Field(type = FieldType.Keyword)
    private String serviceName;

    @Field(type = FieldType.Keyword)
    private String environment;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String rcaSummary;

    @Field(type = FieldType.Date, format = {}, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime createdAt;
}
