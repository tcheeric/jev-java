package xyz.tcheeric.jev;

/**
 * One entry of the evaluator's model list, as {@code GET /v1/models} reports it. The list names
 * aliases as well as versions, and a versioned ID is accepted whether or not it appears here.
 */
public record ModelCard(String name, String description, String releaseDate) {

    public ModelCard {
        Names.require(name, "model name");
    }
}
