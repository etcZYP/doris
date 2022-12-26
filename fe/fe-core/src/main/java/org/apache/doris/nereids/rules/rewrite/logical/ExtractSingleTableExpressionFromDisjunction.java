// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.rewrite.logical;

import org.apache.doris.nereids.rules.Rule;
import org.apache.doris.nereids.rules.RuleType;
import org.apache.doris.nereids.rules.rewrite.OneRewriteRuleFactory;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.util.ExpressionUtils;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Example:
 * (n1.n_name = 'FRANCE' and n2.n_name = 'GERMANY') or (n1.n_name = 'GERMANY' and n2.n_name = 'FRANCE')
 * =>
 * (n1.n_name = 'FRANCE' and n2.n_name = 'GERMANY') or (n1.n_name = 'GERMANY' and n2.n_name = 'FRANCE')
 * and (n1.n_name = 'FRANCE' or n1.n_name='GERMANY') and (n2.n_name='GERMANY' or n2.n_name='FRANCE')
 *
 * (n1.n_name = 'FRANCE' or n1.n_name='GERMANY') is a logical redundant, but it could be pushed down to scan(n1) to
 * reduce the number of scan output tuples.
 * For complete sql example, refer to tpch q7.
 * ==================================================================================================
 * <br/>
 * There are 2 cases, in which the redundant expressions are useless:
 * 1. filter(expr)-->XXX out join.
 * For example, for left join, the redundant expression for right side is not useful, because we cannot push expression
 * down to right child. Refer to PushDownJoinOtherCondition Rule for push-down cases.
 * But it is hard to detect this case, if the outer join is a descendant but not child of the filter.
 * 2. filter(expr)
 *       |-->upper-join
 *             |-->bottom-join
 *             +-->child
 * In current version, we do not extract redundant expression for bottom-join. This redundancy is good for
 * upper-join (reduce the number of input tuple from bottom join), but it becomes unuseful if we rotate the join tree.
 * ==================================================================================================
 *<br/>
 * Implementation note:
 * 1. This rule should only be applied ONCE to avoid generate same redundant expression.
 * 2. This version only generates redundant expressions, but not push them.
 * 3. A redundant expression only contains slots from a single table.
 * 4. This rule is applied after rules converting sub-query to join.
 * 5. Add a flag 'isRedundant' in Expression. It is true, if it is generated by this rule.
 * 6. The useless redundant expression should be removed, if it cannot be pushed down. We need a new rule
 * `RemoveRedundantExpression` to fulfill this purpose.
 * 7. In old optimizer, there is `InferFilterRule` generates redundancy expressions. Its Nereid counterpart also need
 * `RemoveRedundantExpression`.
 */
public class ExtractSingleTableExpressionFromDisjunction extends OneRewriteRuleFactory {
    @Override
    public Rule build() {
        return logicalFilter().whenNot(LogicalFilter::isSingleTableExpressionExtracted).then(filter -> {
            //filter = [(n1.n_name = 'FRANCE' and n2.n_name = 'GERMANY')
            //             or (n1.n_name = 'GERMANY' and n2.n_name = 'FRANCE')]
            //         and ...
            Set<Expression> conjuncts = filter.getConjuncts();

            List<Expression> redundants = Lists.newArrayList();
            for (Expression conjunct : conjuncts) {
                //conjunct=(n1.n_name = 'FRANCE' and n2.n_name = 'GERMANY')
                //          or (n1.n_name = 'GERMANY' and n2.n_name = 'FRANCE')
                List<Expression> disjuncts = ExpressionUtils.extractDisjunction(conjunct);
                //disjuncts={ (n1.n_name = 'FRANCE' and n2.n_name = 'GERMANY'),
                //            (n1.n_name = 'GERMANY' and n2.n_name = 'FRANCE')}
                if (disjuncts.size() == 1) {
                    continue;
                }
                //only check table in first disjunct.
                //In our example, qualifiers = { n1, n2 }
                Expression first = disjuncts.get(0);
                Set<String> qualifiers = first.getInputSlots()
                        .stream()
                        .map(SlotReference.class::cast)
                        .map(this::getSlotQualifierAsString)
                        .collect(Collectors.toSet());
                //try to extract
                for (String qualifier : qualifiers) {
                    List<Expression> extractForAll = Lists.newArrayList();
                    boolean success = true;
                    for (Expression expr : ExpressionUtils.extractDisjunction(conjunct)) {
                        Optional<Expression> extracted = extractSingleTableExpression(expr, qualifier);
                        if (!extracted.isPresent()) {
                            //extract failed
                            success = false;
                            break;
                        } else {
                            extractForAll.add(extracted.get());
                        }
                    }
                    if (success) {
                        redundants.add(ExpressionUtils.or(extractForAll));
                    }
                }

            }
            if (redundants.isEmpty()) {
                return new LogicalFilter<>(filter.getConjuncts(), true, filter.child());
            } else {
                return new LogicalFilter<>(ImmutableSet.<Expression>builder()
                        .addAll(filter.getConjuncts())
                        .addAll(redundants).build(),
                        true, filter.child());
            }
        }).toRule(RuleType.EXTRACT_SINGLE_TABLE_EXPRESSION_FROM_DISJUNCTION);
    }

    private String getSlotQualifierAsString(SlotReference slotReference) {
        StringBuilder builder = new StringBuilder();
        for (String q : slotReference.getQualifier()) {
            builder.append(q).append('.');
        }
        return builder.toString();
    }

    //extract some conjucts from expr, all slots of the extracted conjunct comes from the table referred by qualifier.
    //example: expr=(n1.n_name = 'FRANCE' and n2.n_name = 'GERMANY'), qualifier="n1."
    //output: n1.n_name = 'FRANCE'
    private Optional<Expression> extractSingleTableExpression(Expression expr, String qualifier) {
        List<Expression> output = Lists.newArrayList();
        List<Expression> conjuncts = ExpressionUtils.extractConjunction(expr);
        for (Expression conjunct : conjuncts) {
            if (isSingleTableExpression(conjunct, qualifier)) {
                output.add(conjunct);
            }
        }
        if (output.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(ExpressionUtils.and(output));
        }
    }

    private boolean isSingleTableExpression(Expression expr, String qualifier) {
        //TODO: cache getSlotQualifierAsString() result.
        for (Slot slot : expr.getInputSlots()) {
            String slotQualifier = getSlotQualifierAsString((SlotReference) slot);
            if (!slotQualifier.equals(qualifier)) {
                return false;
            }
        }
        return true;
    }
}
