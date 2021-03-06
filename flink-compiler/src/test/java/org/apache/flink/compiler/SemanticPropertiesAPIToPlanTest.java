/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.compiler;

import org.apache.flink.api.common.functions.JoinFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.operators.base.JoinOperatorBase;
import org.apache.flink.api.common.operators.base.MapOperatorBase;
import org.apache.flink.api.common.operators.base.ReduceOperatorBase;
import org.apache.flink.api.common.operators.util.FieldSet;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;

import org.apache.flink.api.java.operators.translation.JavaPlan;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.tuple.Tuple8;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.compiler.dataproperties.GlobalProperties;
import org.apache.flink.compiler.dataproperties.LocalProperties;
import org.apache.flink.compiler.dataproperties.PartitioningProperty;
import org.apache.flink.compiler.plan.Channel;
import org.apache.flink.compiler.plan.DualInputPlanNode;
import org.apache.flink.compiler.plan.OptimizedPlan;
import org.apache.flink.compiler.plan.PlanNode;
import org.apache.flink.compiler.plan.SingleInputPlanNode;
import org.apache.flink.runtime.operators.shipping.ShipStrategyType;
import org.apache.flink.util.Visitor;
import org.junit.Assert;
import org.junit.Test;

public class SemanticPropertiesAPIToPlanTest extends CompilerTestBase {

	private TupleTypeInfo<Tuple8<Integer, Integer, Integer, Integer, Integer, Integer, Integer, Integer>> tupleInfo =
			new TupleTypeInfo<Tuple8<Integer, Integer, Integer, Integer, Integer, Integer, Integer, Integer>>(
					BasicTypeInfo.INT_TYPE_INFO, BasicTypeInfo.INT_TYPE_INFO, BasicTypeInfo.INT_TYPE_INFO, BasicTypeInfo.INT_TYPE_INFO,
					BasicTypeInfo.INT_TYPE_INFO, BasicTypeInfo.INT_TYPE_INFO, BasicTypeInfo.INT_TYPE_INFO, BasicTypeInfo.INT_TYPE_INFO
			);

	@Test
	public void forwardFieldsTestMapReduce() {
		ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
		DataSet<Tuple3<Integer, Integer, Integer>> set = env.readCsvFile(IN_FILE).types(Integer.class, Integer.class, Integer.class);
		set = set.map(new MockMapper()).withForwardedFields("*")
				.groupBy(0)
				.reduce(new MockReducer()).withForwardedFields("f0->f1")
				.map(new MockMapper()).withForwardedFields("*")
				.groupBy(1)
				.reduce(new MockReducer()).withForwardedFields("*");

		set.print();
		JavaPlan plan = env.createProgramPlan();
		OptimizedPlan oPlan = compileWithStats(plan);

		oPlan.accept(new Visitor<PlanNode>() {
			@Override
			public boolean preVisit(PlanNode visitable) {
				if (visitable instanceof SingleInputPlanNode && visitable.getPactContract() instanceof ReduceOperatorBase) {
					for (Channel input: visitable.getInputs()) {
						GlobalProperties gprops = visitable.getGlobalProperties();
						LocalProperties lprops = visitable.getLocalProperties();

						Assert.assertTrue("Reduce should just forward the input if it is already partitioned",
								input.getShipStrategy() == ShipStrategyType.FORWARD);
						Assert.assertTrue("Wrong GlobalProperties on Reducer",
								gprops.isPartitionedOnFields(new FieldSet(1)));
						Assert.assertTrue("Wrong GlobalProperties on Reducer",
								gprops.getPartitioning() == PartitioningProperty.HASH_PARTITIONED);
						Assert.assertTrue("Wrong LocalProperties on Reducer",
								lprops.getGroupedFields().contains(1));
					}
				}
				if (visitable instanceof SingleInputPlanNode && visitable.getPactContract() instanceof MapOperatorBase) {
					for (Channel input: visitable.getInputs()) {
						GlobalProperties gprops = visitable.getGlobalProperties();
						LocalProperties lprops = visitable.getLocalProperties();

						Assert.assertTrue("Map should just forward the input if it is already partitioned",
								input.getShipStrategy() == ShipStrategyType.FORWARD);
						Assert.assertTrue("Wrong GlobalProperties on Mapper",
								gprops.isPartitionedOnFields(new FieldSet(1)));
						Assert.assertTrue("Wrong GlobalProperties on Mapper",
								gprops.getPartitioning() == PartitioningProperty.HASH_PARTITIONED);
						Assert.assertTrue("Wrong LocalProperties on Mapper",
								lprops.getGroupedFields().contains(1));
					}
					return false;
				}
				return true;
			}

			@Override
			public void postVisit(PlanNode visitable) {

			}
		});
	}

	@Test
	public void forwardFieldsTestJoin() {
		ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
		DataSet<Tuple3<Integer, Integer, Integer>> in1 = env.readCsvFile(IN_FILE).types(Integer.class, Integer.class, Integer.class);
		DataSet<Tuple3<Integer, Integer, Integer>> in2 = env.readCsvFile(IN_FILE).types(Integer.class, Integer.class, Integer.class);
		in1 = in1.map(new MockMapper()).withForwardedFields("*")
				.groupBy(0)
				.reduce(new MockReducer()).withForwardedFields("f0->f1");
		in2 = in2.map(new MockMapper()).withForwardedFields("*")
				.groupBy(1)
				.reduce(new MockReducer()).withForwardedFields("f1->f2");
		DataSet<Tuple3<Integer, Integer, Integer>> out = in1.join(in2).where(1).equalTo(2).with(new MockJoin());

		out.print();
		JavaPlan plan = env.createProgramPlan();
		OptimizedPlan oPlan = compileWithStats(plan);

		oPlan.accept(new Visitor<PlanNode>() {
			@Override
			public boolean preVisit(PlanNode visitable) {
				if (visitable instanceof DualInputPlanNode && visitable.getPactContract() instanceof JoinOperatorBase) {
					DualInputPlanNode node = ((DualInputPlanNode) visitable);

					final Channel inConn1 = node.getInput1();
					final Channel inConn2 = node.getInput2();

					Assert.assertTrue("Join should just forward the input if it is already partitioned",
							inConn1.getShipStrategy() == ShipStrategyType.FORWARD);
					Assert.assertTrue("Join should just forward the input if it is already partitioned",
							inConn2.getShipStrategy() == ShipStrategyType.FORWARD);
					return false;
				}
				return true;
			}

			@Override
			public void postVisit(PlanNode visitable) {

			}
		});
	}

	public static class MockMapper implements MapFunction<Tuple3<Integer, Integer, Integer>, Tuple3<Integer, Integer, Integer>> {
		@Override
		public Tuple3<Integer, Integer, Integer> map(Tuple3<Integer, Integer, Integer> value) throws Exception {
			return null;
		}
	}

	public static class MockReducer implements ReduceFunction<Tuple3<Integer, Integer, Integer>> {
		@Override
		public Tuple3<Integer, Integer, Integer> reduce(Tuple3<Integer, Integer, Integer> value1, Tuple3<Integer, Integer, Integer> value2) throws Exception {
			return null;
		}
	}

	public static class MockJoin implements JoinFunction<Tuple3<Integer, Integer, Integer>,
			Tuple3<Integer, Integer, Integer>, Tuple3<Integer, Integer, Integer>> {

		@Override
		public Tuple3<Integer, Integer, Integer> join(Tuple3<Integer, Integer, Integer> first, Tuple3<Integer, Integer, Integer> second) throws Exception {
			return null;
		}
	}

}

