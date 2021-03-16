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

import com.radixdlt.atommodel.validators.RegisteredValidatorParticle;
import com.radixdlt.engine.StateReducer;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Reduces particles to Registered Validators
 */
public final class ValidatorsReducer implements StateReducer<RegisteredValidators, RegisteredValidatorParticle> {

    public ValidatorsReducer() {
    }

    @Override
    public Class<RegisteredValidators> stateClass() {
        return RegisteredValidators.class;
    }

    @Override
    public Class<RegisteredValidatorParticle> particleClass() {
        return RegisteredValidatorParticle.class;
    }

    @Override
    public Supplier<RegisteredValidators> initial() {
        return RegisteredValidators::create;
    }

    @Override
    public BiFunction<RegisteredValidators, RegisteredValidatorParticle, RegisteredValidators> outputReducer() {
        return (prev, p) -> prev.add(p.getAddress().getPublicKey());
    }

    @Override
    public BiFunction<RegisteredValidators, RegisteredValidatorParticle, RegisteredValidators> inputReducer() {
        return (prev, p) -> prev.remove(p.getAddress().getPublicKey());
    }
}
