/*
 * (C) Copyright 2021 Radix DLT Ltd
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

package com.radixdlt.chaos.mempoolfiller;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * An update event to the mempool filler
 */
public final class MempoolFillerUpdate {
    private final int parallelTransactions;
    private final boolean sendToSelf;

    private MempoolFillerUpdate(int parallelTransactions, boolean sendToSelf) {
        this.parallelTransactions = parallelTransactions;
        this.sendToSelf = sendToSelf;
    }

    public static MempoolFillerUpdate enable(int parallelTransactions, boolean sendToSelf) {
    	if (parallelTransactions < 0) {
    	    throw new IllegalArgumentException("parallelTransactions must be > 0.");
        }
        return new MempoolFillerUpdate(parallelTransactions, sendToSelf);
    }

    public static MempoolFillerUpdate disable() {
        return new MempoolFillerUpdate(-1, false);
    }

    public boolean enabled() {
        return parallelTransactions > 0;
    }

    public OptionalInt numTransactions() {
        return parallelTransactions > 0 ? OptionalInt.of(parallelTransactions) : OptionalInt.empty();
    }

    public Optional<Boolean> sendToSelf() {
        return parallelTransactions > 0 ? Optional.of(sendToSelf) : Optional.empty();
    }

    @Override
    public int hashCode() {
        return Objects.hash(parallelTransactions, sendToSelf);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MempoolFillerUpdate)) {
            return false;
        }

        MempoolFillerUpdate other = (MempoolFillerUpdate) o;
        return this.parallelTransactions == other.parallelTransactions
            && this.sendToSelf == other.sendToSelf;
    }
}
