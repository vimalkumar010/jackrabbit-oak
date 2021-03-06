/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.plugins.index.elastic.query.facets;

import com.google.common.collect.AbstractIterator;
import org.apache.jackrabbit.oak.plugins.index.elastic.ElasticIndexDefinition;
import org.apache.jackrabbit.oak.plugins.index.elastic.query.ElasticSearcher;
import org.apache.jackrabbit.oak.plugins.index.elastic.query.ElasticSearcherModel;
import org.apache.jackrabbit.oak.plugins.index.elastic.util.ElasticConstants;
import org.apache.jackrabbit.oak.plugins.index.search.IndexDefinition;
import org.apache.jackrabbit.oak.plugins.index.search.spi.query.FulltextIndex;
import org.apache.jackrabbit.oak.plugins.index.search.util.TapeSampling;
import org.apache.jackrabbit.oak.spi.query.Filter;
import org.apache.jackrabbit.oak.spi.query.QueryIndex;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class StatisticalElasticFacets extends InsecureElasticFacets {
    private static final Logger LOG = LoggerFactory.getLogger(StatisticalElasticFacets.class);

    private final IndexDefinition.SecureFacetConfiguration secureFacetConfiguration;

    public StatisticalElasticFacets(ElasticSearcher searcher, QueryBuilder query,
                                    QueryIndex.IndexPlan plan, IndexDefinition.SecureFacetConfiguration secureFacetConfiguration,
                                    ElasticAggregationData elasticAggregationData) {
        super(searcher, query, plan, elasticAggregationData);
        this.secureFacetConfiguration = secureFacetConfiguration;
    }

    @Override
    public Map<String, List<FulltextIndex.Facet>> getFacets(ElasticIndexDefinition indexDefinition,
                                                            int numberOfFacets) throws IOException {
        Map<String, List<FulltextIndex.Facet>> result = new HashMap<>();
        Map<String, List<FulltextIndex.Facet>> topChildren;
        Filter filter = getPlan().getFilter();
        int hitCount;
        int sampleSize = secureFacetConfiguration.getStatisticalFacetSampleSize();
        ElasticAggregationData aggregationData = getElasticAggregationData();
        if (aggregationData == null || aggregationData.getNumberOfFacets() < numberOfFacets) {
            LOG.warn("Facets and Totalhit count are being retrieved by calling Elasticsearch");
            topChildren = super.getFacets(indexDefinition, numberOfFacets);
            ElasticSearcherModel elasticSearcherModel = new ElasticSearcherModel.ElasticSearcherModelBuilder()
                    .withQuery(getQuery())
                    .withBatchSize(ElasticConstants.ELASTIC_QUERY_BATCH_SIZE)
                    .build();
            SearchResponse docs = getSearcher().search(elasticSearcherModel);
            long totalResults = docs.getHits().getTotalHits().value;
            hitCount = Math.toIntExact(totalResults);
        } else {
            topChildren = changeToFacetList(getElasticAggregationData().getAggregations().getAsMap(), numberOfFacets);
            hitCount = Math.toIntExact(getElasticAggregationData().getTotalDocuments());
        }

        // In case the hit count is less than sample size(A very small reposiotry perhaps)
        // Delegate getting FacetResults to SecureSortedSetDocValuesFacetCounts to get the exact count
        // instead of statistical count. <OAK-8138>
        if (hitCount < sampleSize) {
            LOG.debug("SampleSize: {} is greater than hitcount: {}, Getting secure facet count", sampleSize, hitCount);
            return new SecureElasticFacets(getSearcher(), getQuery(), getPlan()).getFacets(indexDefinition,
                    numberOfFacets);
        }
        long randomSeed = secureFacetConfiguration.getRandomSeed();
        Iterator<SearchHit> docIterator = getMatchingDocIterator(getSearcher(), getQuery());
        Iterator<SearchHit> sampleIterator = docIterator;
        if (sampleSize < hitCount) {
            LOG.debug("SampleSize: {} is less than hitcount: {}, sampling data", sampleSize, hitCount);
            sampleIterator = getSampledMatchingDocIterator(docIterator, randomSeed, hitCount, sampleSize);
        }
        int accessibleSampleCount = ElasticFacetHelper.getAccessibleDocCount(sampleIterator, filter);
        for (String facet : topChildren.keySet()) {
            List<FulltextIndex.Facet> labelAndValues = topChildren.get(facet);
            labelAndValues = updateLabelAndValueIfRequired(labelAndValues, sampleSize, accessibleSampleCount);
            result.put(facet, labelAndValues);
        }
        return result;
    }

    private Iterator<SearchHit> getMatchingDocIterator(ElasticSearcher searcher, QueryBuilder query) {
        return new AbstractIterator<SearchHit>() {
            List<SearchHit> matchingDocuments = new LinkedList<>();
            Iterator<SearchHit> matchingDocsListIterator = matchingDocuments.iterator();
            int from;

            @Override
            protected SearchHit computeNext() {
                try {
                    if (matchingDocsListIterator.hasNext()) {
                        return matchingDocsListIterator.next();
                    } else {
                        ElasticSearcherModel elasticSearcherModel = new ElasticSearcherModel.ElasticSearcherModelBuilder()
                                .withQuery(query)
                                .withBatchSize(ElasticConstants.ELASTIC_QUERY_BATCH_SIZE)
                                .withFrom(from)
                                .build();
                        SearchResponse searchResponse = searcher.search(elasticSearcherModel);
                        SearchHit[] searchHits = searchResponse.getHits().getHits();
                        if (searchHits.length == 0 || searchHits.length < ElasticConstants.ELASTIC_QUERY_BATCH_SIZE) {
                            return endOfData();
                        } else {
                            matchingDocuments = Arrays.asList(searchHits);
                            matchingDocsListIterator = matchingDocuments.iterator();
                            from += ElasticConstants.ELASTIC_QUERY_BATCH_SIZE;
                            return matchingDocsListIterator.next();
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private Iterator<SearchHit> getSampledMatchingDocIterator(Iterator<SearchHit> matchingDocs,
                                                              long randomdSeed, int hitCount, int sampleSize) {
        TapeSampling<SearchHit> tapeSampling = new TapeSampling<>(new Random(randomdSeed), matchingDocs, hitCount, sampleSize);

        return tapeSampling.getSamples();
    }

    private List<FulltextIndex.Facet> updateLabelAndValueIfRequired(List<FulltextIndex.Facet> labelAndValues,
                                                                    int sampleSize, int accessibleCount) {
        if (accessibleCount < sampleSize) {
            int numZeros = 0;
            List<FulltextIndex.Facet> newValues;
            {
                List<FulltextIndex.Facet> proportionedLVs = new LinkedList<>();
                for (FulltextIndex.Facet labelAndValue : labelAndValues) {
                    long count = labelAndValue.getCount() * accessibleCount / sampleSize;
                    if (count == 0) {
                        numZeros++;
                    }
                    proportionedLVs.add(new FulltextIndex.Facet(labelAndValue.getLabel(), Math.toIntExact(count)));
                }
                labelAndValues = proportionedLVs;
            }
            if (numZeros > 0) {
                newValues = new LinkedList<>();
                for (FulltextIndex.Facet lv : labelAndValues) {
                    if (lv.getCount() > 0) {
                        newValues.add(lv);
                    }
                }
            } else {
                newValues = labelAndValues;
            }
            return newValues;
        } else {
            return labelAndValues;
        }
    }
}
