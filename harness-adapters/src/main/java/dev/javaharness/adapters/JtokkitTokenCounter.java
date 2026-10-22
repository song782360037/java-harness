package dev.javaharness.adapters;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import dev.javaharness.spi.TokenCounter;
import java.util.Locale;

/** Offline token estimate for OpenAI-compatible endpoints; unknown model families use the o200k encoding. */
public final class JtokkitTokenCounter implements TokenCounter {
    private static final Encoding CL100K = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    private static final Encoding O200K = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
    private final Encoding encoding;

    public JtokkitTokenCounter(String model) {
        String name = model == null ? "" : model.toLowerCase(Locale.ROOT);
        this.encoding = name.contains("gpt-3.5") || (name.contains("gpt-4") && !name.contains("gpt-4o")) ? CL100K : O200K;
    }

    @Override public int count(String text) {
        return text == null || text.isEmpty() ? 0 : encoding.countTokens(text);
    }
}
