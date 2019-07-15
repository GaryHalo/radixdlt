package org.radix.api.observable;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.radix.atoms.Atom;
import org.radix.containers.BasicContainer;
import com.radixdlt.serialization.DsonOutput;
import com.radixdlt.serialization.DsonOutput.Output;
import com.radixdlt.serialization.SerializerId2;

/**
 * An event description concerning an atom and whether it has been stored or deleted.
 */
@SerializerId2("api.atom_event")
public final class AtomEventDto extends BasicContainer {
	public enum AtomEventType {
		STORE, DELETE
	}

	@Override
	public short VERSION() {
		return 100;
	}

	@JsonProperty("atom")
	@DsonOutput(Output.ALL)
	private final Atom atom;

	private AtomEventType type;

	public AtomEventDto(AtomEventType type, Atom atom) {
		this.type = type;
		this.atom = atom;
	}

	public Atom getAtom() {
		return atom;
	}

	public AtomEventType getType() {
		return type;
	}

	@JsonProperty("type")
	@DsonOutput(Output.ALL)
	private String getTypeString() {
		return this.type.name().toLowerCase();
	}

	@JsonProperty("type")
	private void setTypeString(String type) {
		this.type = AtomEventType.valueOf(type.toUpperCase());
	}
}
