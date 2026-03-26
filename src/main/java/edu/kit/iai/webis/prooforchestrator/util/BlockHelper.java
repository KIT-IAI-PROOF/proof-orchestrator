/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.util;

import edu.kit.iai.webis.proofutils.model.CommunicationType;
import edu.kit.iai.webis.proofutils.wrapper.Block;
import edu.kit.iai.webis.proofutils.wrapper.Input;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BlockHelper {

    private BlockHelper() {
    }

    /**
     * check whether one block has dynamic (i.e. non-static) inputs
     *
     * @return true, if the block has dynamic inputs, false, if not
     */
    public static boolean hasDynamicInputs(Block block) {
        return block.getInputs().values().stream()
                .anyMatch((input) -> (input.getCommunicationType() == CommunicationType.STEPBASED
                        || input.getCommunicationType() == CommunicationType.EVENT));
    }

    /**
     * get the dynamic (i.e. non-static) inputs of a block
     *
     * @return the list of dynamic inputs
     */
    public static List<Input> getDynamicInputs(Block block) {
        return block.getInputs().values().stream()
                .filter((input) -> (input.getCommunicationType() == CommunicationType.STEPBASED
                        || input.getCommunicationType() == CommunicationType.EVENT))
                .collect(Collectors.toList());
    }

    /**
     * get the required dynamic (i.e. non-static) inputs of a block
     *
     * @return the list of required dynamic inputs
     */
    public static List<Input> getRequiredDynamicInputs(Block block) {
        return block.getInputs().values().stream()
                .filter((input) -> (input.getCommunicationType() == CommunicationType.STEPBASED
                        || input.getCommunicationType() == CommunicationType.EVENT)
                        && input.isRequired()
                )
                .collect(Collectors.toList());
    }

    /**
     * get the required static inputs of a block
     *
     * @return the list of required static inputs
     */
    public static List<Input> getRequiredStaticInputs(Block block) {
        return block.getInputs().values().stream()
                .filter((input) -> (
                                input.getCommunicationType() == CommunicationType.STEPBASED_STATIC && input.isRequired()
                        )
                )
                .collect(Collectors.toList());
    }

    /**
     * get the number of dynamic (i.e. non-static) inputs from one block
     *
     * @return the number of dynamic inputs
     * <br><b>Note: </b> this method uses {@link #getDynamicInputs()}
     */
    public static int getNumberOfDynamicInputs(Block block) {
        return getDynamicInputs(block).size();
    }

    /**
     * check whether all required inputs have values assigned
     *
     * @param block                 the block containing the inputs
     * @param appliedInputsForBlock the map of applied inputs
     * @return true, if the inputs have values assigned, false, if not
     */
    public static boolean checkIfAllRequiredInputsHaveValues(Block block, Map<String, String> appliedInputsForBlock) {
        List<Input> requiredStaticInputs = getRequiredStaticInputs(block);
        return requiredStaticInputs == null || requiredStaticInputs.stream().allMatch((final Input ii) -> appliedInputsForBlock.containsKey(ii.getId()));
    }

    /**
     * Get all static input values of a block
     *
     * @param block                 the block containing the inputs
     * @param appliedInputsForBlock the map of applied inputs
     * @return the map containing input values (key=name of the value, value=the value of the input)
     */
    public static Map<String, String> getStaticInputValues(Block block, Map<String, String> appliedInputValues) {
        Map<String, String> valueMappings = new HashMap<String, String>();
        block.getInputs().values().forEach(input -> {
            String value = appliedInputValues.get(input.getId());
            if (value != null) valueMappings.put(input.getName(), value);
        });
        return valueMappings;
    }
}
