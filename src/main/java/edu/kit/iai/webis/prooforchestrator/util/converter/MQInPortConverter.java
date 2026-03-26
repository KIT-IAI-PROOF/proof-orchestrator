/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.util.converter;

import edu.kit.iai.webis.prooforchestrator.io.MQInPort;
import org.springframework.data.elasticsearch.core.mapping.PropertyValueConverter;

import java.util.Map;

public class MQInPortConverter implements PropertyValueConverter {

    @Override
    public Object write(Object value) {
        return value;
    }

    @Override
    public Object read(Object value) {
        if (value instanceof Map esData) {
            MQInPort port = new MQInPort((String) esData.get("name"));
            port.setActualName((String) esData.get("actualName"));
            return port;
        }
        return null;
    }

}
