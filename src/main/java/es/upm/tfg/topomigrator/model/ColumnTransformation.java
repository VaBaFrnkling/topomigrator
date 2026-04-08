package es.upm.tfg.topomigrator.model;

/**
 * Reglas de transformación aplicables a una columna concreta.
 */
public class ColumnTransformation {

    private String rename;
    private String type;
    private Boolean trim;
    private String nullDefault;
    private String caseTransform; // "uppercase" | "lowercase"

    public String getRename() {
        return rename;
    }

    public void setRename(String rename) {
        this.rename = rename;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Boolean getTrim() {
        return trim;
    }

    public void setTrim(Boolean trim) {
        this.trim = trim;
    }

    public String getNullDefault() {
        return nullDefault;
    }

    public void setNullDefault(String nullDefault) {
        this.nullDefault = nullDefault;
    }

    public String getCaseTransform() {
        return caseTransform;
    }

    public void setCaseTransform(String caseTransform) {
        this.caseTransform = caseTransform;
    }
}
