package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.ResumeTokenCodec.Decoded;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The round-trip property for {@link ResumeTokenCodec}, verified over generated {@link Decoded}
 * values (tempdoc 554 — the property floor; oracle class: free / round-trip). Because {@link Decoded}
 * cannot represent {@code seq < 0}, the generator's domain is exactly the codec's domain, so the
 * property is total — a green run is genuine coverage, not a vacuous one.
 */
class ResumeTokenCodecPropertyTest {

  @Property(tries = 1000)
  void decodeOfEncodeIsIdentity(@ForAll("decoded") Decoded value) {
    String token = ResumeTokenCodec.encode(value.streamId(), value.seq(), value.incarnation());
    assertEquals(
        Optional.of(value),
        ResumeTokenCodec.decode(token),
        "decode(encode(x)) must equal Optional.of(x)");
  }

  @Provide
  Arbitrary<Decoded> decoded() {
    Arbitrary<String> slug =
        Combinators.combine(
                Arbitraries.chars().range('a', 'z'),
                Arbitraries.strings()
                    .withChars("abcdefghijklmnopqrstuvwxyz0123456789-")
                    .ofMaxLength(12))
            .as((first, rest) -> first + rest);
    Arbitrary<StreamId> ids =
        Combinators.combine(Arbitraries.of("registry", "surface", "system"), slug)
            .as((kind, s) -> new StreamId(kind + ":" + s));
    Arbitrary<Long> seqs = Arbitraries.longs().greaterOrEqual(0);
    var incarnations = Combinators.combine(Arbitraries.longs(), Arbitraries.longs())
        .as(java.util.UUID::new);
    return Combinators.combine(ids, seqs, incarnations).as(Decoded::new);
  }
}
