/*
 * (C) Copyright 2021 Radix DLT Ltd
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
 */

package com.radixdlt.statecomputer;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.radixdlt.DefaultSerialization;
import com.radixdlt.consensus.Command;
import com.radixdlt.constraintmachine.CMMicroInstruction;
import com.radixdlt.constraintmachine.DataPointer;
import com.radixdlt.counters.SystemCounters;
import com.radixdlt.engine.RadixEngine;
import com.radixdlt.engine.RadixEngineErrorCode;
import com.radixdlt.engine.RadixEngineException;
import com.radixdlt.identifiers.AID;
import com.radixdlt.mempool.Mempool;
import com.radixdlt.mempool.MempoolDuplicateException;
import com.radixdlt.mempool.MempoolFullException;
import com.radixdlt.mempool.MempoolMaxSize;
import com.radixdlt.mempool.MempoolRejectedException;
import com.radixdlt.atom.Atom;
import com.radixdlt.serialization.DeserializeException;
import com.radixdlt.utils.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A mempool which uses internal radix engine to be more efficient.
 */
public final class RadixEngineMempool implements Mempool<Atom> {
	private final ConcurrentHashMap<AID, Command> data = new ConcurrentHashMap<>();
	private final Map<CMMicroInstruction, Set<AID>> particleIndex = new HashMap<>();
	private final int maxSize;
	private final SystemCounters counters;
	private final RadixEngine<Atom, LedgerAndBFTProof> radixEngine;

	@Inject
	public RadixEngineMempool(
		RadixEngine<Atom, LedgerAndBFTProof> radixEngine,
		@MempoolMaxSize int maxSize,
		SystemCounters counters
	) {
		if (maxSize <= 0) {
			throw new IllegalArgumentException("mempool.maxSize must be positive: " + maxSize);
		}
		this.radixEngine = radixEngine;
		this.maxSize = maxSize;
		this.counters = Objects.requireNonNull(counters);
	}

	@Override
	public void add(Command command) throws MempoolRejectedException {
		Atom atom;
		try {
			atom = DefaultSerialization.getInstance().fromDson(command.getPayload(), Atom.class);
		} catch (DeserializeException e) {
			throw new MempoolRejectedException("Deserialize failure.");
		}

		if (this.data.size() >= this.maxSize) {
			throw new MempoolFullException(
				String.format("Mempool full: %s of %s items", this.data.size(), this.maxSize)
			);
		}

		if (this.data.containsKey(atom.getAID())) {
			throw new MempoolDuplicateException(String.format("Mempool already has command %s", atom.getAID()));
		}

		try {
			RadixEngine.RadixEngineBranch<Atom, LedgerAndBFTProof> checker = radixEngine.transientBranch();
			checker.execute(List.of(atom));
		} catch (RadixEngineException e) {
			// TODO: allow missing dependency atoms to live for a certain amount of time
			throw new RadixEngineMempoolException(e);
		} finally {
			radixEngine.deleteBranches();
		}

		this.data.put(atom.getAID(), command);

		atom.uniqueInstructions()
			.forEach(i -> particleIndex.merge(i, Set.of(atom.getAID()), Sets::union));

		updateCounts();
	}

	@Override
	public List<Pair<Atom, Exception>> committed(List<Atom> commands) {
		commands.forEach(a -> data.remove(a.getAID()));
		final List<Pair<Atom, Exception>> removed = new ArrayList<>();
		commands.stream()
			.flatMap(Atom::uniqueInstructions)
			.flatMap(p -> {
				Set<AID> aids = particleIndex.remove(p);
				return aids != null ? aids.stream() : Stream.empty();
			})
			.forEach(aid -> {
				var command = data.remove(aid);
				// TODO: Cleanup
				if (command != null) {
					Atom atom;
					try {
						atom = DefaultSerialization.getInstance().fromDson(command.getPayload(), Atom.class);
					} catch (DeserializeException e) {
						throw new IllegalStateException();
					}

					removed.add(Pair.of(atom, new RadixEngineMempoolException(
						new RadixEngineException(
							atom,
							RadixEngineErrorCode.CM_ERROR,
							"State conflict",
							DataPointer.ofAtom()
						)
					)));
				}
			});

		updateCounts();
		return removed;
	}

	// TODO: Order by highest fees paid
	@Override
	public List<Command> getCommands(int count, Set<Atom> prepared) {
		var copy = new HashSet<>(data.keySet());
		prepared.stream()
			.flatMap(Atom::uniqueInstructions)
			.distinct()
			.flatMap(i -> particleIndex.getOrDefault(i, Set.of()).stream())
			.distinct()
			.forEach(copy::remove);

		return copy.stream().map(data::get).limit(count).collect(Collectors.toList());
	}

	private void updateCounts() {
		this.counters.set(SystemCounters.CounterType.MEMPOOL_COUNT, this.data.size());
		this.counters.set(SystemCounters.CounterType.MEMPOOL_MAXCOUNT, this.maxSize);
	}

	@Override
	public String toString() {
		return String.format("%s[%x:%s/%s]",
				getClass().getSimpleName(), System.identityHashCode(this), this.data.size(), this.maxSize);
	}
}
