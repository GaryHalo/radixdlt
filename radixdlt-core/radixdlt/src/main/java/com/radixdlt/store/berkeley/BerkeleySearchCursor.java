/*
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
 */

package com.radixdlt.store.berkeley;

import com.radixdlt.identifiers.AID;
import com.radixdlt.store.SearchCursor;
import com.radixdlt.utils.Longs;
import org.bouncycastle.util.Arrays;

import java.util.Objects;

/**
 * A Tempo implementation of a {@link SearchCursor}
 */
public class BerkeleySearchCursor implements SearchCursor {
	private final byte[] primary;
	private final byte[] data;
	private final BerkeleyLedgerEntryStore store;

	BerkeleySearchCursor(BerkeleyLedgerEntryStore store, byte[] primary, byte[] data) {
		this.primary = Arrays.clone(Objects.requireNonNull(primary));
		this.store = store;
		this.data = data;
	}

	public byte[] getPrimary() {
		return this.primary;
	}

	@Override
	public long getStateVersion() {
		return Longs.fromByteArray(this.primary, 0);
	}

	@Override
	public AID get() {
		return AID.from(this.data, Long.BYTES);
	}

	@Override
	public SearchCursor next() {
		return this.store.getNext(this);
	}
}
