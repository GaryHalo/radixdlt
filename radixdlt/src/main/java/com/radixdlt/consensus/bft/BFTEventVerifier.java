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

package com.radixdlt.consensus.bft;

import com.radixdlt.consensus.BFTEventProcessor;
import com.radixdlt.consensus.HashVerifier;
import com.radixdlt.consensus.Hasher;
import com.radixdlt.consensus.NewView;
import com.radixdlt.consensus.Proposal;
import com.radixdlt.consensus.View;
import com.radixdlt.consensus.Vote;
import com.radixdlt.consensus.VoteData;
import com.radixdlt.crypto.ECDSASignature;
import com.radixdlt.crypto.ECPublicKey;
import com.radixdlt.crypto.Hash;
import com.radixdlt.utils.Longs;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class BFTEventVerifier implements BFTEventProcessor {
	private static final Logger log = LogManager.getLogger();

	private final BFTNode self;
	private final BFTEventProcessor forwardTo;
	private final Hasher hasher;
	private final HashVerifier verifier;

	public BFTEventVerifier(
		BFTNode self,
		BFTEventProcessor forwardTo,
		Hasher hasher,
		HashVerifier verifier
	) {
		this.self = Objects.requireNonNull(self);
		this.hasher = Objects.requireNonNull(hasher);
		this.verifier = Objects.requireNonNull(verifier);
		this.forwardTo = forwardTo;
	}

	@Override
	public void start() {
		forwardTo.start();
	}

	@Override
	public void processVote(Vote vote) {
		final VoteData voteData = vote.getVoteData();
		final Hash voteHash = this.hasher.hash(voteData);
		// TODO: Remove IllegalArgumentException
		final ECDSASignature signature = vote.getSignature().orElseThrow(() -> new IllegalArgumentException("vote is missing signature"));
		final BFTNode node = vote.getAuthor();
		final ECPublicKey key = node.getKey();
		if (!this.verifier.verify(key, voteHash, signature)) {
			log.info("{}: Ignoring invalid signature from author {}", self::getSimpleName, node::getSimpleName);
			return;
		}

		forwardTo.processVote(vote);
	}

	@Override
	public void processNewView(NewView newView) {
		final BFTNode node = newView.getAuthor();
		final ECPublicKey key = node.getKey();
		final Hash newViewId = Hash.of(Longs.toByteArray(newView.getView().number()));
		// TODO: Remove IllegalArgumentException
		final ECDSASignature signature = newView.getSignature().orElseThrow(() -> new IllegalArgumentException("new-view is missing signature"));
		if (!this.verifier.verify(key, newViewId, signature)) {
			log.info("{}: Ignoring invalid signature from author {}", self::getSimpleName, node::getSimpleName);
			return;
		}

		forwardTo.processNewView(newView);
	}

	@Override
	public void processProposal(Proposal proposal) {
		final BFTNode node = proposal.getAuthor();
		final ECPublicKey key = node.getKey();
		final Hash vertexHash = this.hasher.hash(proposal.getVertex());
		final ECDSASignature signature = proposal.getSignature().orElseThrow(() -> new IllegalArgumentException("proposal is missing signature"));
		if (!this.verifier.verify(key, vertexHash, signature)) {
			log.info("{}: Ignoring invalid signature from author {}", self::getSimpleName, node::getSimpleName);
			return;
		}

		forwardTo.processProposal(proposal);
	}

	@Override
	public void processLocalTimeout(View view) {
		forwardTo.processLocalTimeout(view);
	}

	@Override
	public void processLocalSync(Hash vertexId) {
		forwardTo.processLocalSync(vertexId);
	}
}
