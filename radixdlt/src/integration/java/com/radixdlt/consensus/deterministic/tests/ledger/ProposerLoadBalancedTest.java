/*
 *
 * (C) Copyright 2020 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 */

package com.radixdlt.consensus.deterministic.tests.ledger;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.radixdlt.consensus.NewView;
import com.radixdlt.consensus.deterministic.DeterministicTest;
import com.radixdlt.consensus.deterministic.NodeWeighting;
import com.radixdlt.counters.SystemCounters.CounterType;
import com.radixdlt.utils.UInt256;

import static org.assertj.core.api.Assertions.*;

public class ProposerLoadBalancedTest {

	private ImmutableList<Long> run(int numNodes, long numViews, NodeWeighting weighting) {
		final DeterministicTest test = DeterministicTest.createSingleEpochAlwaysSyncedTest(numNodes, weighting);
		test.start();

		final AtomicBoolean running = new AtomicBoolean(true);
		final Random random = new Random(123456);
		while (running.get()) {
			test.processNextMsg(random, msg -> {
				if (msg instanceof NewView) {
					NewView nv = (NewView) msg;
					if (nv.getView().number() > numViews) {
						running.set(false);
					}
				}
				return running.get();
			});
		}

		return IntStream.range(0, numNodes)
			.mapToObj(test::getSystemCounters)
			.map(counters -> counters.get(CounterType.CONSENSUS_PROPOSALS_MADE))
			.collect(ImmutableList.toImmutableList());
	}

	@Test
	public void when_run_2_nodes_with_very_different_weights__then_proposals_should_match() {
		final long proposalChunk = 100_000L; // Actually proposalChunk + 1 proposals run
		ImmutableList<Long> proposals = this.run(2, proposalChunk + 1, NodeWeighting.repeatingSequence(1, proposalChunk));
		assertThat(proposals).containsExactly(1L, proposalChunk);
	}

	@Test
	public void when_run_3_nodes_with_equal_weight__then_proposals_should_be_equal() {
		final long proposalsPerNode = 50_000L;
		ImmutableList<Long> proposals = this.run(3, 3 * proposalsPerNode, NodeWeighting.constant(UInt256.ONE));
		assertThat(proposals).allMatch(l -> l == proposalsPerNode);
	}

	@Test
	public void when_run_100_nodes_with_equal_weight__then_proposals_should_be_equal() {
		final long proposalsPerNode = 100L;
		ImmutableList<Long> proposals = this.run(100, 100 * proposalsPerNode, NodeWeighting.constant(UInt256.ONE));
		assertThat(proposals).allMatch(l -> l == proposalsPerNode);
	}

	@Test
	public void when_run_3_nodes_with_linear_weights__then_proposals_should_match() {
		final long proposalChunk = 10_000L; // Actually 3! * proposalChunk proposals run
		List<Long> proposals = this.run(3, 1 * 2 * 3 * proposalChunk, NodeWeighting.repeatingSequence(1, 2, 3));
		assertThat(proposals).containsExactly(proposalChunk, 2 * proposalChunk, 3 * proposalChunk);
	}

	@Test
	public void when_run_100_nodes_with_two_different_weights__then_proposals_should_match() {
		// Nodes 0..49 have weight 1; nodes 50..99 have weight 2
		final long proposalChunk = 100L; // Actually 150 * proposalChunk proposals run
		ImmutableList<Long> proposals = this.run(100, 150 * proposalChunk, index -> UInt256.from(index / 50 + 1)); // Weights 1, 1, ..., 2, 2
		assertThat(proposals.subList(0, 50)).allMatch(Long.valueOf(proposalChunk)::equals);
		assertThat(proposals.subList(50, 100)).allMatch(Long.valueOf(2 * proposalChunk)::equals);
	}

	@Test
	public void when_run_3_nodes_with_large_lcm_weighting__then_proposals_should_be_proportional() {
		final long numProposals = 200_000L;
		ImmutableList<UInt256> weights = ImmutableList.of(
			// Some large primes with product/LCM > 2^64 but < 2^256
			UInt256.from("941083981"),
			UInt256.from("961748927"),
			UInt256.from("982451653")
		);
		UInt256 sum = weights.stream().reduce(UInt256.ZERO, UInt256::add);
		UInt256 numViews256 = UInt256.from(numProposals);
		long[] values = weights.stream()
			.map(w -> w.multiply(numViews256).divide(sum))
			.mapToLong(v -> v.getLow().getLow())
			.toArray();
		ImmutableList<Long> proposals = this.run(3, numProposals, weights::get);
		// Correct number of total proposals
		assertThat(proposals.stream().mapToLong(Long::longValue).sum()).isEqualTo(numProposals);
		// Same as calculated value, +/- 1 (rounding and ordering)
		for (int i = 0; i < values.length; ++i) {
			assertThat(proposals.get(i).longValue()).isBetween(values[i] - 1, values[i] + 1);
		}
	}

	@Test
	public void when_run_100_nodes_with_very_large_period__then_proposals_should_be_proportional() {
		final long numProposals = 10_000L;
		ImmutableList<UInt256> weights = generatePrimes(100)
			.mapToObj(UInt256::from)
			.collect(ImmutableList.toImmutableList());
		UInt256 sum = weights.stream().reduce(UInt256.ZERO, UInt256::add);
		UInt256 numViews256 = UInt256.from(numProposals);
		long[] values = weights.stream()
			.map(w -> w.multiply(numViews256).divide(sum))
			.mapToLong(v -> v.getLow().getLow())
			.toArray();
		ImmutableList<Long> proposals = this.run(100, numProposals, weights::get);
		// Correct number of total proposals
		assertThat(proposals.stream().mapToLong(Long::longValue).sum()).isEqualTo(numProposals);
		// Same as calculated value, +/- 1 (rounding and ordering)
		for (int i = 0; i < values.length; ++i) {
			assertThat(proposals.get(i).longValue()).isBetween(values[i] - 1, values[i] + 1);
		}
	}

	private static LongStream generatePrimes(int n) {
		// Just FYI, doesn't include 2.  You don't need it.
		return LongStream.iterate(3L, m -> m + 2)
			.filter(ProposerLoadBalancedTest::isPrime)
			.limit(n);
	}

	private static boolean isPrime(long number) {
		return LongStream.rangeClosed(1L, (long) Math.sqrt(number) / 2L)
			.map(n -> n * 2 + 1)
			.noneMatch(n -> number % n == 0);
	}
}
