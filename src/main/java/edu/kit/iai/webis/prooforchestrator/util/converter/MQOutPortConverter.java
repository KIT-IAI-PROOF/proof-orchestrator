/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.util.converter;

import edu.kit.iai.webis.prooforchestrator.io.MQOutPort;
import org.springframework.data.elasticsearch.core.mapping.PropertyValueConverter;

import java.util.Map;

public class MQOutPortConverter implements PropertyValueConverter {

    @Override
    public Object write(Object value) {
        return value;
    }

    @Override
    public Object read(Object value) {
        if (value instanceof Map esData) {
            return new MQOutPort((String) esData.get("exchangeName"));
        }
        return null;
    }

}
