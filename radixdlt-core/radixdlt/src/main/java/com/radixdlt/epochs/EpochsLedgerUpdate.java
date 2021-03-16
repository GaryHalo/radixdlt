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

package com.radixdlt.epochs;

import com.radixdlt.consensus.epoch.EpochChange;
import com.radixdlt.ledger.LedgerUpdate;
import java.util.Objects;
import java.util.Optional;

/**
 * A ledger update with possible epoch related information
 */
public final class EpochsLedgerUpdate {
	private final LedgerUpdate base;
	private final EpochChange epochChange;

	public EpochsLedgerUpdate(LedgerUpdate base, EpochChange epochChange) {
		this.base = Objects.requireNonNull(base);
		this.epochChange = epochChange;
	}

	public LedgerUpdate getBase() {
		return base;
	}

	public Optional<EpochChange> getEpochChange() {
		return Optional.ofNullable(epochChange);
	}

	@Override
	public String toString() {
		if (this.epochChange == null) {
			return String.format(
				"%s{base=%s}", getClass().getSimpleName(), this.base
			);
		} else {
			return String.format(
				"%s{epoch=%s,view=%s,base=%s}",
				getClass().getSimpleName(), this.epochChange.getProof().getEpoch(), this.epochChange.getProof().getView(), base
			);
		}
	}

	@Override
	public int hashCode() {
		return Objects.hash(base, epochChange);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof EpochsLedgerUpdate)) {
			return false;
		}

		EpochsLedgerUpdate other = (EpochsLedgerUpdate) o;
		return Objects.equals(this.base, other.base)
			&& Objects.equals(this.epochChange, other.epochChange);
	}
}
