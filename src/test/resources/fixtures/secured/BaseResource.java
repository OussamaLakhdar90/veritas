package secured;

/** Base DTO — its fields are inherited by subclasses (exercises SymbolSolver / inherited-field extraction). */
public class BaseResource {
    private String id;

    public String getId() {
        return id;
    }
}
