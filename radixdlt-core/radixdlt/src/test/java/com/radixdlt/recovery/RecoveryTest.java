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

package com.radixdlt.recovery;

import org.assertj.core.api.Condition;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import com.radixdlt.CryptoModule;
import com.radixdlt.PersistedNodeForTestingModule;
import com.radixdlt.atom.Atom;
import com.radixdlt.atommodel.system.SystemParticle;
import com.radixdlt.consensus.Proposal;
import com.radixdlt.consensus.LedgerProof;
import com.radixdlt.consensus.Vote;
import com.radixdlt.consensus.bft.BFTNode;
import com.radixdlt.consensus.bft.Self;
import com.radixdlt.consensus.bft.View;
import com.radixdlt.consensus.epoch.EpochView;
import com.radixdlt.consensus.epoch.EpochViewUpdate;
import com.radixdlt.consensus.epoch.Epoched;
import com.radixdlt.consensus.liveness.ScheduledLocalTimeout;
import com.radixdlt.consensus.safety.PersistentSafetyStateStore;
import com.radixdlt.consensus.safety.SafetyState;
import com.radixdlt.counters.SystemCounters;
import com.radixdlt.counters.SystemCountersImpl;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.engine.RadixEngine;
import com.radixdlt.environment.EventProcessor;
import com.radixdlt.environment.ProcessOnDispatch;
import com.radixdlt.environment.deterministic.ControlledSenderFactory;
import com.radixdlt.environment.deterministic.DeterministicEpochsConsensusProcessor;
import com.radixdlt.environment.deterministic.DeterministicSavedLastEvent;
import com.radixdlt.environment.deterministic.network.ControlledMessage;
import com.radixdlt.environment.deterministic.network.DeterministicNetwork;
import com.radixdlt.environment.deterministic.network.MessageMutator;
import com.radixdlt.environment.deterministic.network.MessageSelector;
import com.radixdlt.mempool.MempoolMaxSize;
import com.radixdlt.mempool.MempoolThrottleMs;
import com.radixdlt.network.addressbook.PeersView;
import com.radixdlt.statecomputer.EpochCeilingView;
import com.radixdlt.statecomputer.LedgerAndBFTProof;
import com.radixdlt.statecomputer.checkpoint.Genesis;
import com.radixdlt.statecomputer.checkpoint.MockedGenesisAtomModule;
import com.radixdlt.store.DatabaseEnvironment;
import com.radixdlt.store.DatabaseLocation;
import com.radixdlt.store.LastEpochProof;
import com.radixdlt.store.berkeley.BerkeleyLedgerEntryStore;
import com.radixdlt.sync.CommittedReader;
import io.reactivex.rxjava3.schedulers.Timed;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that on restarts (simulated via creation of new injectors) that the application
 * state is the same as last seen.
 */
@RunWith(Parameterized.class)
public class RecoveryTest {

	@Parameters
	public static Collection<Object[]> parameters() {
		return List.of(new Object[][]{
			{10L}, {1000000L}
		});
	}

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private DeterministicNetwork network;
	private Injector currentInjector;
	private ECKeyPair ecKeyPair = ECKeyPair.generateNew();
	private final ECKeyPair universeKey = ECKeyPair.generateNew();
	private final long epochCeilingView;

	@Inject
	@Genesis
	private Atom genesisAtom;

	public RecoveryTest(long epochCeilingView) {
		this.epochCeilingView = epochCeilingView;
		this.network = new DeterministicNetwork(
			List.of(BFTNode.create(ecKeyPair.getPublicKey())),
			MessageSelector.firstSelector(),
			MessageMutator.nothing()
		);
	}

	@Before
	public void setup() {
		Guice.createInjector(
			new MockedGenesisAtomModule(),
			new CryptoModule(),
			new AbstractModule() {
				@Override
				public void configure() {
					bind(SystemCounters.class).toInstance(new SystemCountersImpl());
					bind(ECKeyPair.class).annotatedWith(Names.named("universeKey")).toInstance(universeKey);
					bind(new TypeLiteral<ImmutableList<ECKeyPair>>() { }).annotatedWith(Genesis.class)
						.toInstance(ImmutableList.of(ecKeyPair));
				}
			}
		).injectMembers(this);

		this.currentInjector = createRunner(ecKeyPair);
		this.currentInjector.getInstance(DeterministicEpochsConsensusProcessor.class).start();
	}

	@After
	public void teardown() {
		if (this.currentInjector != null) {
			var ledgerStore = this.currentInjector.getInstance(BerkeleyLedgerEntryStore.class);
			ledgerStore.close();
			var safetyStore = this.currentInjector.getInstance(PersistentSafetyStateStore.class);
			safetyStore.close();
			var dbEnv = this.currentInjector.getInstance(DatabaseEnvironment.class);
			dbEnv.stop();
		}
	}

	private Injector createRunner(ECKeyPair ecKeyPair) {
		final BFTNode self = BFTNode.create(ecKeyPair.getPublicKey());

		return Guice.createInjector(
			new AbstractModule() {
				@Override
				protected void configure() {
					bindConstant().annotatedWith(Names.named("magic")).to(0);
					bind(Atom.class).annotatedWith(Genesis.class).toInstance(genesisAtom);
					bind(PeersView.class).toInstance(List::of);
					bind(ECKeyPair.class).annotatedWith(Self.class).toInstance(ecKeyPair);
					bind(ECKeyPair.class).annotatedWith(Names.named("universeKey")).toInstance(universeKey);
					bind(new TypeLiteral<List<BFTNode>>() { }).toInstance(ImmutableList.of(self));
					bind(ControlledSenderFactory.class).toInstance(network::createSender);
					bind(View.class).annotatedWith(EpochCeilingView.class).toInstance(View.of(epochCeilingView));
					bindConstant().annotatedWith(MempoolMaxSize.class).to(10);
					bindConstant().annotatedWith(MempoolThrottleMs.class).to(10L);
					bindConstant().annotatedWith(DatabaseLocation.class)
						.to(folder.getRoot().getAbsolutePath() + "/RADIXDB_RECOVERY_TEST_" + self);
					Multibinder.newSetBinder(binder(), new TypeLiteral<EventProcessor<Vote>>() { }, ProcessOnDispatch.class)
						.addBinding().to(new TypeLiteral<DeterministicSavedLastEvent<Vote>>() { });
					bind(new TypeLiteral<DeterministicSavedLastEvent<Vote>>() { }).in(Scopes.SINGLETON);
				}
			},
			new PersistedNodeForTestingModule()
		);
	}

	private RadixEngine<Atom, LedgerAndBFTProof> getRadixEngine() {
		return currentInjector.getInstance(Key.get(new TypeLiteral<RadixEngine<Atom, LedgerAndBFTProof>>() { }));
	}

	private CommittedReader getCommittedReader() {
		return currentInjector.getInstance(CommittedReader.class);
	}

	private EpochView getLastEpochView() {
		return currentInjector.getInstance(Key.get(new TypeLiteral<DeterministicSavedLastEvent<EpochViewUpdate>>() { }))
			.getLastEvent().getEpochView();
	}

	private Vote getLastVote() {
		return currentInjector.getInstance(Key.get(new TypeLiteral<DeterministicSavedLastEvent<Vote>>() { })).getLastEvent();
	}

	private void restartNode() {
		this.network.dropMessages(m -> m.channelId().receiverIndex() == 0 && m.channelId().senderIndex() == 0);
		this.currentInjector = createRunner(ecKeyPair);
		DeterministicEpochsConsensusProcessor processor = currentInjector.getInstance(DeterministicEpochsConsensusProcessor.class);
		processor.start();
	}

	private void processForCount(int messageCount) {
		for (int i = 0; i < messageCount; i++) {
			Timed<ControlledMessage> msg = this.network.nextMessage();
			DeterministicEpochsConsensusProcessor runner = currentInjector
				.getInstance(DeterministicEpochsConsensusProcessor.class);
			runner.handleMessage(msg.value().origin(), msg.value().message());
		}
	}

	@Test
	public void on_reboot_should_load_same_computed_state() {
		// Arrange
		processForCount(100);
		var radixEngine = getRadixEngine();
		SystemParticle systemParticle = radixEngine.getComputedState(SystemParticle.class);

		// Act
		restartNode();

		// Assert
		var restartedRadixEngine = getRadixEngine();
		SystemParticle restartedSystemParticle = restartedRadixEngine.getComputedState(SystemParticle.class);
		assertThat(restartedSystemParticle).isEqualTo(systemParticle);
	}

	@Test
	public void on_reboot_should_load_same_last_header() {
		// Arrange
		processForCount(100);
		var reader = getCommittedReader();
		Optional<LedgerProof> proof = reader.getLastProof();

		// Act
		restartNode();

		// Assert
		var restartedReader = getCommittedReader();
		Optional<LedgerProof> restartedProof = restartedReader.getLastProof();
		assertThat(restartedProof).isEqualTo(proof);
	}

	@Test
	public void on_reboot_should_load_same_last_epoch_header() {
		// Arrange
		processForCount(100);
		EpochView epochView = getLastEpochView();

		// Act
		restartNode();

		// Assert
		LedgerProof restartedEpochProof = currentInjector.getInstance(
			Key.get(LedgerProof.class, LastEpochProof.class)
		);

		assertThat(restartedEpochProof.isEndOfEpoch()).isTrue();
		assertThat(restartedEpochProof.getEpoch()).isEqualTo(epochView.getEpoch() - 1);
	}

	@Test
	public void on_reboot_should_load_same_last_vote() {
		// Arrange
		processForCount(100);
		Vote vote = getLastVote();

		// Act
		restartNode();

		// Assert
		SafetyState safetyState = currentInjector.getInstance(SafetyState.class);
		assertThat(safetyState.getLastVotedView()).isEqualTo(vote.getView());
	}

	@Test
	public void on_reboot_should_only_emit_pacemaker_events() {
		// Arrange
		processForCount(100);

		// Act
		restartNode();

		// Assert
		assertThat(network.allMessages())
			.hasSize(3)
			.haveExactly(
				1,
				new Condition<>(
					msg -> Epoched.isInstance(msg.message(), ScheduledLocalTimeout.class),
					"A single epoched scheduled timeout has been emitted"
				)
			)
			.haveExactly(
				1,
				new Condition<>(
					msg -> msg.message() instanceof ScheduledLocalTimeout,
					"A single scheduled timeout update has been emitted"
				)
			)
			.haveExactly(
				1,
				new Condition<>(
					msg -> msg.message() instanceof Proposal,
					"A proposal has been emitted"
				)
			);
	}
}
