/*
 * (C) Copyright 2020 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.radixdlt.integration.distributed.simulation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.RateLimiter;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.google.inject.util.Modules;
import com.radixdlt.ConsensusRunnerModule;
import com.radixdlt.FunctionalNodeModule;
import com.radixdlt.integration.distributed.simulation.monitors.SimulationNodeEventsModule;
import com.radixdlt.statecomputer.checkpoint.Genesis;
import com.radixdlt.statecomputer.checkpoint.MockedGenesisAtomModule;
import com.radixdlt.MockedCryptoModule;
import com.radixdlt.MockedPersistenceStoreModule;
import com.radixdlt.consensus.bft.Self;
import com.radixdlt.environment.rx.RxEnvironmentModule;
import com.radixdlt.identifiers.RadixAddress;
import com.radixdlt.integration.distributed.MockedAddressBookModule;
import com.radixdlt.mempool.MempoolMaxSize;
import com.radixdlt.mempool.MempoolThrottleMs;
import com.radixdlt.network.addressbook.PeersView;
import com.radixdlt.store.MockedRadixEngineStoreModule;
import com.radixdlt.sync.MockedCommittedReaderModule;
import com.radixdlt.sync.MockedLedgerStatusUpdatesRunnerModule;
import com.radixdlt.sync.SyncRunnerModule;
import com.radixdlt.consensus.bft.PacemakerMaxExponent;
import com.radixdlt.consensus.bft.PacemakerRate;
import com.radixdlt.consensus.bft.PacemakerTimeout;
import com.radixdlt.consensus.bft.View;
import com.radixdlt.consensus.bft.BFTNode;
import com.radixdlt.consensus.sync.BFTSyncPatienceMillis;
import com.radixdlt.counters.SystemCounters;
import com.radixdlt.counters.SystemCountersImpl;
import com.radixdlt.recovery.MockedRecoveryModule;
import com.radixdlt.integration.distributed.simulation.TestInvariant.TestInvariantError;
import com.radixdlt.integration.distributed.simulation.application.BFTValidatorSetNodeSelector;
import com.radixdlt.integration.distributed.simulation.application.CommandGenerator;
import com.radixdlt.integration.distributed.simulation.application.EpochsNodeSelector;
import com.radixdlt.integration.distributed.simulation.application.NodeSelector;
import com.radixdlt.integration.distributed.simulation.monitors.NodeEvents;
import com.radixdlt.integration.distributed.simulation.application.LocalMempoolPeriodicSubmitter;
import com.radixdlt.integration.distributed.simulation.network.SimulationNodes;
import com.radixdlt.integration.distributed.simulation.network.SimulationNodes.RunningNetwork;
import com.radixdlt.middleware2.network.GetVerticesRequestRateLimit;
import com.radixdlt.consensus.bft.BFTValidator;
import com.radixdlt.consensus.bft.BFTValidatorSet;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.integration.distributed.simulation.network.SimulationNetwork;
import com.radixdlt.statecomputer.EpochCeilingView;
import com.radixdlt.statecomputer.MaxValidators;
import com.radixdlt.statecomputer.MinValidators;
import com.radixdlt.statecomputer.ValidatorSetBuilder;
import com.radixdlt.sync.SyncConfig;
import com.radixdlt.utils.DurationParser;
import com.radixdlt.utils.Pair;
import com.radixdlt.utils.UInt256;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * High level BFT Simulation Test Runner
 */
public class SimulationTest {
	private static final String ENVIRONMENT_VAR_NAME = "TEST_DURATION"; // Same as used by regression test suite
	private static final Duration DEFAULT_TEST_DURATION = Duration.ofSeconds(30);

	public interface SimulationNetworkActor {
		void start(RunningNetwork network);
		void stop();
	}

	private final ImmutableList<ECKeyPair> nodes;
	private final SimulationNetwork simulationNetwork;
	private final Module testModule;
	private final Module baseNodeModule;
	private final Module overrideModule;
	private final Map<ECKeyPair, Module> byzantineNodeModules;

	private SimulationTest(
		ImmutableList<ECKeyPair> nodes,
		SimulationNetwork simulationNetwork,
		Module baseNodeModule,
		Module overrideModule,
		Map<ECKeyPair, Module> byzantineNodeModules,
		Module testModule
	) {
		this.nodes = nodes;
		this.simulationNetwork = simulationNetwork;
		this.baseNodeModule = baseNodeModule;
		this.overrideModule = overrideModule;
		this.byzantineNodeModules = byzantineNodeModules;
		this.testModule = testModule;
	}

	public static class Builder {
		private enum LedgerType {
			MOCKED_LEDGER(false, true, false, false, false, false, false),
			LEDGER(false, true, true, false, false, false, false),
			LEDGER_AND_SYNC(false, true, true, false, false, false, true),
			LEDGER_AND_LOCALMEMPOOL(false, true, true, true, false, false, false),
			LEDGER_AND_EPOCHS(false, true, true, false, false, true, false),
			LEDGER_AND_EPOCHS_AND_SYNC(false, true, true, false, false, true, true),
			LEDGER_AND_LOCALMEMPOOL_AND_EPOCHS_AND_RADIXENGINE(true, true, true, true, true, true, false),
			FULL_FUNCTION(true, true, true, true, true, true, true);

			private final boolean hasSharedMempool;
			private final boolean hasConsensus;
			private final boolean hasSync;

			// State manager
			private final boolean hasLedger;
			private final boolean hasMempool;
			private final boolean hasRadixEngine;

			private final boolean hasEpochs;

			LedgerType(
				boolean hasSharedMempool,
				boolean hasConsensus,
				boolean hasLedger,
				boolean hasMempool,
				boolean hasRadixEngine,
				boolean hasEpochs,
				boolean hasSync
			) {
				this.hasSharedMempool = hasSharedMempool;
				this.hasConsensus = hasConsensus;
				this.hasLedger = hasLedger;
				this.hasMempool = hasMempool;
				this.hasRadixEngine = hasRadixEngine;
				this.hasEpochs = hasEpochs;
				this.hasSync = hasSync;
			}
		}

		private ImmutableList<ECKeyPair> nodes = ImmutableList.of(ECKeyPair.generateNew());
		private long pacemakerTimeout = 12 * SimulationNetwork.DEFAULT_LATENCY;
		private LedgerType ledgerType = LedgerType.MOCKED_LEDGER;

		private Module initialNodesModule;
		private final ImmutableList.Builder<Module> testModules = ImmutableList.builder();
		private final ImmutableList.Builder<Module> modules = ImmutableList.builder();
		private Module networkModule;
		private Module overrideModule = null;
		private Function<ImmutableList<ECKeyPair>, ImmutableMap<ECKeyPair, Module>> byzantineModuleCreator = i -> ImmutableMap.of();

		// TODO: Fix pacemaker so can Default 1 so can debug in IDE, possibly from properties at some point
		// TODO: Specifically, simulation test with engine, epochs and mempool gets stuck on a single validator
		private final int minValidators = 2;
		private int maxValidators = Integer.MAX_VALUE;

		private Builder() {
		}

		public Builder addSingleByzantineModule(Module byzantineModule) {
			this.byzantineModuleCreator = nodes -> ImmutableMap.of(nodes.get(0), byzantineModule);
			return this;
		}

		public Builder addByzantineModuleToAll(Module byzantineModule) {
			this.byzantineModuleCreator = nodes -> nodes.stream()
				.collect(ImmutableMap.<ECKeyPair, ECKeyPair, Module>toImmutableMap(n -> n, n -> byzantineModule));
			return this;
		}

		public Builder overrideWithIncorrectModule(Module module) {
			this.overrideModule = module;
			return this;
		}

		public Builder networkModules(Module... networkModules) {
			this.networkModule = Modules.combine(networkModules);
			return this;
		}

		public Builder addNetworkModule(Module networkModule) {
			this.networkModule = Modules.combine(this.networkModule, networkModule);
			return this;
		}

		public Builder pacemakerTimeout(long pacemakerTimeout) {
			this.pacemakerTimeout = pacemakerTimeout;
			return this;
		}

		public Builder numNodes(int numNodes, int numInitialValidators, int maxValidators, Iterable<UInt256> initialStakes) {
			this.maxValidators = maxValidators;
			this.nodes = Stream.generate(ECKeyPair::generateNew)
				.limit(numNodes)
				.collect(ImmutableList.toImmutableList());

			final var stakesIterator = repeatLast(initialStakes);
			final var initialStakesMap = nodes.stream()
				.collect(ImmutableMap.toImmutableMap(ECKeyPair::getPublicKey, k -> stakesIterator.next()));

			final var vsetBuilder = ValidatorSetBuilder.create(this.minValidators, numInitialValidators);
			final var initialVset = vsetBuilder.buildValidatorSet(initialStakesMap);
			if (initialVset == null) {
				throw new IllegalStateException(
					String.format(
						"Can't build a validator set between %s and %s validators from %s",
						this.minValidators, numInitialValidators, initialStakesMap
					)
				);
			}

			final var bftNodes = initialStakesMap.keySet().stream()
				.map(BFTNode::create)
				.collect(ImmutableList.toImmutableList());
			final var validators = initialStakesMap.entrySet().stream()
				.map(e -> BFTValidator.from(BFTNode.create(e.getKey()), e.getValue()))
				.collect(ImmutableList.toImmutableList());

			this.initialNodesModule = new AbstractModule() {
				@Override
				protected void configure() {
					bind(new TypeLiteral<ImmutableList<BFTNode>>() { }).toInstance(bftNodes);
					bind(new TypeLiteral<ImmutableList<BFTValidator>>() { }).toInstance(validators);
				}
			};

			modules.add(new AbstractModule() {
				@Override
				protected void configure() {
					bind(new TypeLiteral<ImmutableList<BFTNode>>() { }).toInstance(bftNodes);
					bind(new TypeLiteral<ImmutableList<BFTValidator>>() { }).toInstance(validators);
					bind(BFTValidatorSet.class).toInstance(initialVset);
				}
			});

			return this;
		}

		public Builder numNodes(int numNodes, int numInitialValidators, Iterable<UInt256> initialStakes) {
			return numNodes(numNodes, numInitialValidators, maxValidators, initialStakes);
		}

		public Builder numNodes(int numNodes, int numInitialValidators, int maxValidators) {
			return numNodes(numNodes, numInitialValidators, maxValidators, ImmutableList.of(UInt256.ONE));
		}

		public Builder numNodes(int numNodes, int numInitialValidators) {
			return numNodes(numNodes, numInitialValidators, ImmutableList.of(UInt256.ONE));
		}

		public Builder numNodes(int numNodes) {
			return numNodes(numNodes, numNodes);
		}

		public Builder ledgerAndEpochs(View epochHighView, Function<Long, IntStream> epochToNodeIndexMapper) {
			this.ledgerType = LedgerType.LEDGER_AND_EPOCHS;
			this.modules.add(new AbstractModule() {
				@Override
				protected void configure() {
					bind(View.class).annotatedWith(EpochCeilingView.class).toInstance(epochHighView);
				}

				@Provides
				public Function<Long, BFTValidatorSet> epochToNodeMapper() {
					return epochToNodeIndexMapper.andThen(indices -> BFTValidatorSet.from(
						indices.mapToObj(nodes::get)
							.map(node -> BFTNode.create(node.getPublicKey()))
							.map(node -> BFTValidator.from(node, UInt256.ONE))
							.collect(Collectors.toList())));
				}
			});

			return this;
		}

		public Builder ledger() {
			this.ledgerType = LedgerType.LEDGER;
			return this;
		}

		public Builder ledgerAndSync(SyncConfig syncConfig) {
			this.ledgerType = LedgerType.LEDGER_AND_SYNC;
			modules.add(new AbstractModule() {
				@Override
				protected void configure() {
					bind(SyncConfig.class).toInstance(syncConfig);
				}

				@Provides
				@Singleton
				PeersView peersView(@Self BFTNode self) {
					return () -> nodes.stream()
						.map(k -> BFTNode.create(k.getPublicKey()))
						.filter(n -> !n.equals(self))
						.collect(Collectors.toList());
				}
			});
			return this;
		}

		public Builder fullFunctionNodes(
			View epochHighView,
			SyncConfig syncConfig
		) {
			final ECKeyPair universeKey = ECKeyPair.generateNew();
			this.ledgerType = LedgerType.FULL_FUNCTION;
			modules.add(new AbstractModule() {
				@Override
				protected void configure() {
					bind(ECKeyPair.class).annotatedWith(Names.named("universeKey")).toInstance(universeKey);
					bind(View.class).annotatedWith(EpochCeilingView.class).toInstance(epochHighView);
					bind(SyncConfig.class).toInstance(syncConfig);
					bind(Integer.class).annotatedWith(MinValidators.class).toInstance(minValidators);
					bind(Integer.class).annotatedWith(MaxValidators.class).toInstance(maxValidators);
					bind(new TypeLiteral<List<BFTNode>>() { }).toInstance(List.of());
					install(new MockedGenesisAtomModule());
				}

				@Provides
				@Genesis
				ImmutableList<ECKeyPair> validators() {
					return nodes;
				}

				@Provides
				@Self
				private RadixAddress radixAddress(@Named("magic") int magic, @Self BFTNode self) {
					return new RadixAddress((byte) magic, self.getKey());
				}

				@Provides
				@Singleton
				PeersView peersView(@Self BFTNode self) {
					return () -> nodes.stream()
						.map(k -> BFTNode.create(k.getPublicKey()))
						.filter(n -> !n.equals(self))
						.collect(Collectors.toList());
				}
			});
			return this;
		}

		public Builder ledgerAndEpochsAndSync(
			View epochHighView,
			Function<Long, IntStream> epochToNodeIndexMapper,
			SyncConfig syncConfig
		) {
			this.ledgerType = LedgerType.LEDGER_AND_EPOCHS_AND_SYNC;
			modules.add(new AbstractModule() {
				@Override
				protected void configure() {
					bind(View.class).annotatedWith(EpochCeilingView.class).toInstance(epochHighView);
					bind(SyncConfig.class).toInstance(syncConfig);
				}

				@Provides
				public Function<Long, BFTValidatorSet> epochToNodeMapper() {
					return epochToNodeIndexMapper.andThen(indices -> BFTValidatorSet.from(
						indices.mapToObj(nodes::get)
							.map(node -> BFTNode.create(node.getPublicKey()))
							.map(node -> BFTValidator.from(node, UInt256.ONE))
							.collect(Collectors.toList())));
				}

				@Provides
				@Singleton
				PeersView peersView(@Self BFTNode self) {
					return () -> nodes.stream()
						.map(k -> BFTNode.create(k.getPublicKey()))
						.filter(n -> !n.equals(self))
						.collect(Collectors.toList());
				}
			});
			return this;
		}

		public Builder ledgerAndMempool() {
			this.ledgerType = LedgerType.LEDGER_AND_LOCALMEMPOOL;
			this.modules.add(new AbstractModule() {
				public void configure() {
					bindConstant().annotatedWith(MempoolMaxSize.class).to(10);
					bindConstant().annotatedWith(MempoolThrottleMs.class).to(10L);
				}
			});
			return this;
		}

		public Builder ledgerAndRadixEngineWithEpochHighView(View epochHighView) {
			final ECKeyPair universeKey = ECKeyPair.generateNew();

			this.ledgerType = LedgerType.LEDGER_AND_LOCALMEMPOOL_AND_EPOCHS_AND_RADIXENGINE;
			this.modules.add(new AbstractModule() {
				@Override
				protected void configure() {
					bind(ECKeyPair.class).annotatedWith(Names.named("universeKey")).toInstance(universeKey);
					bind(new TypeLiteral<List<BFTNode>>() { }).toInstance(List.of());
					bindConstant().annotatedWith(MempoolMaxSize.class).to(100);
					bindConstant().annotatedWith(MempoolThrottleMs.class).to(10L);
					bind(View.class).annotatedWith(EpochCeilingView.class).toInstance(epochHighView);
					bind(Integer.class).annotatedWith(MinValidators.class).toInstance(minValidators);
					bind(Integer.class).annotatedWith(MaxValidators.class).toInstance(maxValidators);
					install(new MockedGenesisAtomModule());
				}

				@Provides
				@Genesis
				ImmutableList<ECKeyPair> validators() {
					return nodes;
				}

				@Provides
				@Self
				private RadixAddress radixAddress(@Named("magic") int magic, @Self BFTNode self) {
					return new RadixAddress((byte) magic, self.getKey());
				}

				@Provides
				@Singleton
				PeersView peersView(@Self BFTNode self) {
					return () -> nodes.stream()
							.map(k -> BFTNode.create(k.getPublicKey()))
							.filter(n -> !n.equals(self))
							.collect(Collectors.toList());
				}
			});

			return this;
		}

		public Builder addNodeModule(Module module) {
			this.modules.add(module);
			return this;
		}

		public Builder addTestModules(Module... modules) {
			this.testModules.add(modules);
			return this;
		}

		public Builder addMempoolSubmissionsSteadyState(CommandGenerator commandGenerator) {
			NodeSelector nodeSelector = this.ledgerType.hasEpochs ? new EpochsNodeSelector() : new BFTValidatorSetNodeSelector();
			this.testModules.add(new AbstractModule() {
				@Override
				public void configure() {
					var multibinder = Multibinder.newSetBinder(binder(), SimulationNetworkActor.class);
					multibinder.addBinding().to(LocalMempoolPeriodicSubmitter.class);
				}

				@Provides
				@Singleton
				LocalMempoolPeriodicSubmitter mempoolSubmittor() {
					return new LocalMempoolPeriodicSubmitter(
						commandGenerator,
						nodeSelector
					);
				}
			});

			return this;
		}

		public Builder addActor(Class<? extends SimulationNetworkActor> c) {
			this.testModules.add(new AbstractModule() {
				@Override
				public void configure() {
					Multibinder.newSetBinder(binder(), SimulationNetworkActor.class).addBinding().to(c);
				}
			});
			return this;
		};

		public SimulationTest build() {
			final NodeEvents nodeEvents = new NodeEvents();

			// Config
			modules.add(new AbstractModule() {
				@Override
				public void configure() {
					bind(SystemCounters.class).to(SystemCountersImpl.class).in(Scopes.SINGLETON);
					bindConstant().annotatedWith(BFTSyncPatienceMillis.class).to(200);
					bindConstant().annotatedWith(PacemakerTimeout.class).to(pacemakerTimeout);
					bindConstant().annotatedWith(PacemakerRate.class).to(2.0);
					bindConstant().annotatedWith(PacemakerMaxExponent.class).to(0); // Use constant timeout for now
					bind(RateLimiter.class).annotatedWith(GetVerticesRequestRateLimit.class).toInstance(RateLimiter.create(50.0));
					bind(NodeEvents.class).toInstance(nodeEvents);
				}
			});
			modules.add(new MockedSystemModule());
			modules.add(new MockedCryptoModule());
			modules.add(new MockedAddressBookModule());

			// Functional
			modules.add(new FunctionalNodeModule(
				ledgerType.hasConsensus,
				ledgerType.hasLedger,
				ledgerType.hasMempool,
				ledgerType.hasSharedMempool,
				ledgerType.hasRadixEngine,
				ledgerType.hasEpochs,
				ledgerType.hasSync
			));

			// Persistence
			if (ledgerType.hasRadixEngine) {
				modules.add(new MockedRadixEngineStoreModule());
			}
			modules.add(new MockedPersistenceStoreModule());
			modules.add(new MockedRecoveryModule());

			// Testing
			modules.add(new SimulationNodeEventsModule());
			testModules.add(new AbstractModule() {
				@Override
				protected void configure() {
					Multibinder.newSetBinder(binder(), SimulationNetworkActor.class);
					bind(Key.get(new TypeLiteral<List<ECKeyPair>>() { })).toInstance(nodes);
					bind(NodeEvents.class).toInstance(nodeEvents);
				}
			});

			// Nodes
			final SimulationNetwork simulationNetwork = Guice.createInjector(
				initialNodesModule,
				new SimulationNetworkModule(),
				networkModule
			).getInstance(SimulationNetwork.class);

			// Runners
			modules.add(new RxEnvironmentModule());
			if (ledgerType.hasConsensus) {
				if (!ledgerType.hasEpochs) {
					modules.add(new MockedConsensusRunnerModule());
				} else {
					modules.add(new ConsensusRunnerModule());
				}
			}
			if (ledgerType.hasLedger && ledgerType.hasSync) {
				modules.add(new MockedCommittedReaderModule());
				if (!ledgerType.hasEpochs) {
					modules.add(new MockedSyncRunnerModule());
				} else {
					modules.add(new SyncRunnerModule());
				}
			} else if (ledgerType.hasEpochs && !ledgerType.hasSync) {
				modules.add(new MockedLedgerStatusUpdatesRunnerModule());
			}

			return new SimulationTest(
				nodes,
				simulationNetwork,
				Modules.combine(modules.build()),
				overrideModule,
				byzantineModuleCreator.apply(this.nodes),
				Modules.combine(testModules.build())
			);
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	private Observable<Pair<Monitor, Optional<TestInvariantError>>> runChecks(
		Set<SimulationNetworkActor> runners,
		Map<Monitor, TestInvariant> checkers,
		RunningNetwork runningNetwork,
		Duration duration
	) {
		List<Pair<Monitor, Observable<Pair<Monitor, TestInvariantError>>>> assertions = checkers.keySet().stream()
			.map(name -> {
				TestInvariant check = checkers.get(name);
				return
					Pair.of(
						name,
						check.check(runningNetwork).map(e -> Pair.of(name, e)).publish().autoConnect(2)
					);
			})
			.collect(Collectors.toList());

		Single<Monitor> firstErrorSignal = Observable.merge(assertions.stream().map(Pair::getSecond).collect(Collectors.toList()))
			.firstOrError()
			.map(Pair::getFirst);

		List<Single<Pair<Monitor, Optional<TestInvariantError>>>> results = assertions.stream()
			.map(assertion -> assertion.getSecond()
				.takeUntil(firstErrorSignal.flatMapObservable(name ->
					!assertion.getFirst().equals(name) ? Observable.just(name) : Observable.never()))
				.takeUntil(Observable.timer(duration.get(ChronoUnit.SECONDS), TimeUnit.SECONDS))
				.map(e -> Optional.of(e.getSecond()))
				.first(Optional.empty())
				.map(result -> Pair.of(assertion.getFirst(), result))
			)
			.collect(Collectors.toList());

		return Single.merge(results).toObservable()
			.doOnSubscribe(d -> runners.forEach(r -> r.start(runningNetwork)));
	}

	/**
	 * Runs the test for time configured via environment variable. If environment variable is missing then
	 * default duration is used. Returns either once the duration has passed or if a check has failed.
	 * Returns a map from the check name to the result.
	 *
	 * @return map of check results
	 */
	public RunningSimulationTest run() {
		return run(getConfiguredDuration(), ImmutableMap.of());
	}

	public RunningSimulationTest run(Duration duration) {
		return run(duration, ImmutableMap.of());
	}

	/**
	 * Get test duration.
	 *
	 * @return configured test duration.
	 */
	public static Duration getConfiguredDuration() {
		return Optional.ofNullable(System.getenv(ENVIRONMENT_VAR_NAME))
				.flatMap(DurationParser::parse)
				.orElse(DEFAULT_TEST_DURATION);
	}

	/**
	 * Runs the test for a given time. Returns either once the duration has passed or if a check has failed.
	 * Returns a map from the check name to the result.
	 *
	 * @param duration duration to run test for
	 * @param disabledModuleRunners a list of disabled module runners by node index
	 * @return test results
	 */
	public RunningSimulationTest run(
		Duration duration,
		ImmutableMap<Integer, ImmutableSet<String>> disabledModuleRunners
	) {
		Injector testInjector = Guice.createInjector(testModule);
		var runners = testInjector.getInstance(Key.get(new TypeLiteral<Set<SimulationNetworkActor>>() { }));
		var checkers = testInjector.getInstance(Key.get(new TypeLiteral<Map<Monitor, TestInvariant>>() { }));

		SimulationNodes bftNetwork = new SimulationNodes(
			nodes,
			simulationNetwork,
			baseNodeModule,
			overrideModule,
			byzantineNodeModules
		);
		RunningNetwork runningNetwork = bftNetwork.start(disabledModuleRunners);

		final var resultObservable = runChecks(runners, checkers, runningNetwork, duration)
			.doFinally(() -> {
				runners.forEach(SimulationNetworkActor::stop);
				bftNetwork.stop();
			});

		return new RunningSimulationTest(resultObservable, runningNetwork);
	}

	private static <T> Iterator<T> repeatLast(Iterable<T> iterable) {
		final var iterator = iterable.iterator();
		if (!iterator.hasNext()) {
			throw new IllegalArgumentException("Can't repeat an empty iterable");
		}
		return new Iterator<>() {
			T lastValue = null;

			@Override
			public boolean hasNext() {
				return true;
			}

			@Override
			public T next() {
				if (iterator.hasNext()) {
					this.lastValue = iterator.next();
				}
				return this.lastValue;
			}
		};
	}

	public static final class RunningSimulationTest {

		private final Observable<Pair<Monitor, Optional<TestInvariantError>>> resultObservable;
		private final RunningNetwork network;

		private RunningSimulationTest(
			Observable<Pair<Monitor, Optional<TestInvariantError>>> resultObservable,
			RunningNetwork network
		) {
			this.resultObservable = resultObservable;
			this.network = network;
		}

		public RunningNetwork getNetwork() {
			return network;
		}

		public Map<Monitor, Optional<TestInvariantError>> awaitCompletion() {
			return this.resultObservable
				.blockingStream()
				.collect(Collectors.toMap(Pair::getFirst, Pair::getSecond));
		}
	}
}
