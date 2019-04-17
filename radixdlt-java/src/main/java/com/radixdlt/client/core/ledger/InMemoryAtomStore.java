package com.radixdlt.client.core.ledger;

import com.google.common.collect.ImmutableSet;
import com.radixdlt.client.core.atoms.Atom;
import com.radixdlt.client.core.atoms.particles.Particle;
import com.radixdlt.client.core.atoms.particles.Spin;
import com.radixdlt.client.core.ledger.AtomObservation.Type;
import com.radixdlt.client.core.ledger.AtomObservation.AtomObservationUpdateType;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.radixdlt.client.atommodel.accounts.RadixAddress;

/**
 * An in memory storage of atoms and particles
 */
public class InMemoryAtomStore implements AtomStore {
	private final Map<Atom, AtomObservation> atoms = new ConcurrentHashMap<>();
	private final Map<Particle, Map<Spin, Set<Atom>>> particleIndex = new ConcurrentHashMap<>();

	private final Map<RadixAddress, CopyOnWriteArrayList<ObservableEmitter<AtomObservation>>> allObservers = new ConcurrentHashMap<>();
	private final Map<RadixAddress, CopyOnWriteArrayList<ObservableEmitter<Long>>> allSyncers = new ConcurrentHashMap<>();

	private final Object lock = new Object();
	private final Map<RadixAddress, Boolean> syncedMap = new HashMap<>();

	private void softDeleteDependentsOf(Atom atom) {
		atom.particles(Spin.UP)
			.forEach(p -> {
				Map<Spin, Set<Atom>> particleSpinIndex = particleIndex.get(p);
				particleSpinIndex.getOrDefault(Spin.DOWN, Collections.emptySet())
					.forEach(a -> {
						AtomObservation observation = atoms.get(a);
						if (observation.getAtom().equals(atom)) {
							return;
						}

						if (observation.getUpdateType().getType() == Type.STORE || !observation.getUpdateType().isSoft()) {
							// This first so that leaves get deleted first
							softDeleteDependentsOf(observation.getAtom());

							atoms.put(observation.getAtom(), AtomObservation.softDeleted(observation.getAtom()));
						}
					});
			});
	}

	/**
	 * Store an atom under a given destination
	 * TODO: add synchronization if needed
	 *
	 * @param address address to store under
	 * @param atomObservation the atom to store
	 */
	public void store(RadixAddress address, AtomObservation atomObservation) {
		synchronized (lock) {
			final boolean synced = atomObservation.isHead();
			syncedMap.put(address, synced);

			final Atom atom = atomObservation.getAtom();
			if (atom != null) {

				final AtomObservation curObservation = atoms.get(atom);
				final AtomObservationUpdateType nextUpdate = atomObservation.getUpdateType();
				final AtomObservationUpdateType lastUpdate = curObservation != null ? curObservation.getUpdateType() : null;

				final boolean include;
				if (lastUpdate == null) {
					include = nextUpdate.getType() == Type.STORE;
					atom.spunParticles().forEach(s -> {
						Map<Spin, Set<Atom>> spinParticleIndex = particleIndex.get(s.getParticle());
						if (spinParticleIndex == null) {
							spinParticleIndex = new EnumMap<>(Spin.class);
							particleIndex.put(s.getParticle(), spinParticleIndex);
						}
						spinParticleIndex.merge(
							s.getSpin(),
							Collections.singleton(atom),
							(a, b) -> new ImmutableSet.Builder<Atom>().addAll(a).addAll(b).build()
						);
					});
				} else {
					// Soft observation should not be able to update a hard state
					// Only update if type changes
					include = (!nextUpdate.isSoft() || lastUpdate.isSoft())
						&& nextUpdate.getType() != lastUpdate.getType();
				}

				if (nextUpdate.getType() == Type.DELETE && include) {
					softDeleteDependentsOf(atom);
				}

				final boolean isSoftToHard = lastUpdate != null && lastUpdate.isSoft() && !nextUpdate.isSoft();
				if (include || isSoftToHard) {
					atoms.put(atom, atomObservation);
				}

				if (include) {
					final CopyOnWriteArrayList<ObservableEmitter<AtomObservation>> observers = allObservers.get(address);
					if (observers != null) {
						observers.forEach(e -> e.onNext(atomObservation));
					}
				}
			} else {
				final CopyOnWriteArrayList<ObservableEmitter<AtomObservation>> observers = allObservers.get(address);
				if (observers != null) {
					observers.forEach(e -> e.onNext(atomObservation));
				}
			}

			if (synced) {
				final CopyOnWriteArrayList<ObservableEmitter<Long>> syncers = allSyncers.get(address);
				if (syncers != null) {
					syncers.forEach(e -> e.onNext(System.currentTimeMillis()));
				}
			}
		}
	}

	@Override
	public Observable<Long> onSync(RadixAddress address) {
		return Observable.create(emitter -> {
			synchronized (lock) {
				if (syncedMap.getOrDefault(address, false)) {
					emitter.onNext(System.currentTimeMillis());
				}

				final CopyOnWriteArrayList<ObservableEmitter<Long>> syncers;
				if (!allSyncers.containsKey(address)) {
					syncers = new CopyOnWriteArrayList<>();
					allSyncers.put(address, syncers);
				} else {
					syncers = allSyncers.get(address);
				}
				syncers.add(emitter);
				emitter.setCancellable(() -> syncers.remove(emitter));
			}
		});
	}

	@Override
	public Stream<Particle> getUpParticles(RadixAddress address) {
		synchronized (lock) {
			return particleIndex.entrySet().stream()
				.filter(e -> {
					if (!e.getKey().getShardables().contains(address)) {
						return false;
					}
					final Map<Spin, Set<Atom>> spinParticleIndex = e.getValue();
					final boolean hasDown = spinParticleIndex.getOrDefault(Spin.DOWN, Collections.emptySet())
						.stream().anyMatch(a -> atoms.get(a).isStore());
					return !hasDown && spinParticleIndex.getOrDefault(Spin.UP, Collections.emptySet())
						.stream().anyMatch(a -> atoms.get(a).isStore());
				})
				.map(Map.Entry::getKey);
		}
	}

	@Override
	public Observable<AtomObservation> getAtomObservations(RadixAddress address) {
		return Observable.create(emitter -> {
			synchronized (lock) {
				final CopyOnWriteArrayList<ObservableEmitter<AtomObservation>> observers;
				if (!allObservers.containsKey(address)) {
					observers = new CopyOnWriteArrayList<>();
					allObservers.put(address, observers);
				} else {
					observers = allObservers.get(address);
				}
				observers.add(emitter);
				atoms.entrySet().stream()
					.filter(e -> e.getValue().isStore() && e.getKey().addresses().anyMatch(address::equals))
					.map(Map.Entry::getValue)
					.forEach(emitter::onNext);
				if (syncedMap.getOrDefault(address, false)) {
					emitter.onNext(AtomObservation.head());
				}
				emitter.setCancellable(() -> {
					synchronized (lock) {
						observers.remove(emitter);
					}
				});
			}
		});
	}
}
