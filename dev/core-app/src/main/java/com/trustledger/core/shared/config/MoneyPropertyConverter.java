package com.trustledger.core.shared.config;

import com.trustledger.core.shared.Money;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds configuration amounts such as {@code "10000.00"} to {@link Money} (INR) with {@link Money#parse}: no
 * floating point, at most 2 decimals, never rounded. A bad value fails startup. Write amounts as quoted
 * strings in YAML, so YAML never reads them as floats.
 */
@Component
@ConfigurationPropertiesBinding
public class MoneyPropertyConverter implements Converter<String, Money> {

    @Override
    public Money convert(String source) {
        return Money.parse(source.strip());
    }
}
