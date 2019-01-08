package com.radixdlt.client.core.network.epics;

import com.radixdlt.client.core.network.RadixNetworkEpic;
import com.radixdlt.client.core.network.RadixNetworkState;
import com.radixdlt.client.core.network.RadixNodeAction;
import com.radixdlt.client.core.network.RadixNode;
import com.radixdlt.client.core.network.actions.AddNodeAction;
import com.radixdlt.client.core.network.actions.GetLivePeersRequestAction;
import com.radixdlt.client.core.network.actions.GetLivePeersResultAction;
import com.radixdlt.client.core.network.actions.GetNodeDataRequestAction;
import com.radixdlt.client.core.network.actions.SubmitAtomAction;
import com.radixdlt.client.core.network.actions.FetchAtomsObservationAction;
import io.reactivex.Maybe;
import io.reactivex.Observable;

public class DiscoverNodesEpic implements RadixNetworkEpic {
	private final Observable<RadixNode> seeds;

	public DiscoverNodesEpic(Observable<RadixNode> seeds) {
		this.seeds = seeds;
	}

	@Override
	public Observable<RadixNodeAction> epic(Observable<RadixNodeAction> updates, Observable<RadixNetworkState> networkState) {
		Observable<RadixNode> connectedSeeds = updates
			.filter(u -> u instanceof SubmitAtomAction || u instanceof FetchAtomsObservationAction)
			.firstOrError()
			.flatMapObservable(i -> seeds)
			.publish()
			.autoConnect(3);

		Observable<RadixNodeAction> addSeeds = connectedSeeds.map(AddNodeAction::of);
		Observable<RadixNodeAction> addSeedData = connectedSeeds.map(GetNodeDataRequestAction::of);
		Observable<RadixNodeAction> addSeedSiblings = connectedSeeds.map(GetLivePeersRequestAction::of);

		Observable<RadixNodeAction> addNodes = updates
			.filter(u -> u instanceof GetLivePeersResultAction)
			.map(GetLivePeersResultAction.class::cast)
			.flatMap(u ->
				Observable.combineLatest(
					Observable.fromIterable(u.getResult()),
					networkState.firstOrError().toObservable(),
					(data, s) -> {
						RadixNode node = new RadixNode(data.getIp(), u.getNode().isSsl(), u.getNode().getPort());

						if (!s.getNodes().containsKey(node)) {
							return Maybe.just(AddNodeAction.of(node, data));
						} else {
							return Maybe.<RadixNodeAction>empty();
						}
					}
				).flatMapMaybe(i -> i)
			);

		return addSeeds.mergeWith(addSeedData).mergeWith(addSeedSiblings).mergeWith(addNodes);
	}
}
