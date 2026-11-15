package fr.elhadi.raglearning.services;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Retire la mise en forme Slack qui n'a pas de sens pour un modèle d'embedding :
 * caractères illisibles, étiquettes d'espace de travail, codes d'emoji, gras, champs vides du
 * formulaire du bot, espaces et lignes vides en trop.
 * <p>
 * Il est appelé par {@link SlackAnonymizer} <b>avant</b> l'anonymisation : aucune transformation
 * du texte ne doit avoir lieu après, sous peine de recomposer une donnée que les règles n'avaient
 * pas reconnue.
 * <p>
 * Ce gabarit se répète dans des centaines de fils : laissé tel quel, il rapproche
 * les vecteurs de fils sans rapport entre eux et resserre les scores de similarité.
 * Les valeurs des champs (environnement, description…) sont conservées.
 */
@Component
public class SlackMarkupCleaner {

    /**
     * Caractères illisibles : zone d'usage privé Unicode et caractère de remplacement (U+FFFD).
     * Retirés en premier : s'ils coupaient une donnée sensible, elle est recomposée ici, avant
     * l'anonymisation, et non après.
     */
    private static final Pattern UNREADABLE_CHARACTERS = Pattern.compile("\\p{Co}|\\uFFFD");

    /** Étiquette ajoutée par le bot, par exemple {@code _tag:devportal_}. */
    private static final Pattern WORKSPACE_TAG = Pattern.compile("_tag:[\\w-]+_");

    /**
     * Code d'emoji Slack ({@code :smile:}, {@code :+1:}). Il doit être précédé d'un espace,
     * d'un autre emoji ou du début du texte, et contenir une lettre ou un {@code +} :
     * les heures ({@code 10:30:45}) et les paires {@code a:b:c} ne sont pas touchées.
     */
    private static final Pattern EMOJI_SHORTCODE = Pattern.compile(
        "(?<![^\\s:]):(?=[a-z0-9_+'-]*[a-z+])[a-z0-9_+'-]+:(?![^\\s.,;!?):])"
    );

    /** Champ du formulaire laissé vide, seul sur sa ligne : {@code *Javascript error*}. */
    private static final Pattern EMPTY_FIELD_LINE =
        Pattern.compile("(?m)^\\h*\\*[^*\\n]+\\*\\h*$");

    /** Libellé de champ en gras suivi de sa valeur : {@code *Environment * UAT1}. */
    private static final Pattern FIELD_LABEL = Pattern.compile(
        "(?m)^(\\h*)\\*([^*\\n]*?[^*\\s:])\\h*:?\\h*\\*\\h*(?=\\S)"
    );

    /** Gras dans une phrase : {@code *public*}. Une multiplication ({@code 2 * 3}) n'est pas touchée. */
    private static final Pattern INLINE_BOLD =
        Pattern.compile("(?<![\\w*])\\*([^*\\s][^*\\n]*?)\\*(?![\\w*])");

    private static final Pattern MULTIPLE_SPACES = Pattern.compile("\\h{2,}");

    private static final Pattern LINE_EDGE_SPACES = Pattern.compile("(?m)^\\h+|\\h+$");

    /** Plusieurs lignes vides réduites à une seule : la frontière entre paragraphes est conservée. */
    private static final Pattern MULTIPLE_LINE_BREAKS = Pattern.compile("\\R{2,}");

    public String clean(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final String readable = UNREADABLE_CHARACTERS.matcher(text).replaceAll("");
        final String withoutTags = WORKSPACE_TAG.matcher(readable).replaceAll("");
        final String withoutEmojis = EMOJI_SHORTCODE.matcher(withoutTags).replaceAll("");
        final String withoutEmptyFields = EMPTY_FIELD_LINE.matcher(withoutEmojis).replaceAll("");
        final String withPlainLabels = FIELD_LABEL.matcher(withoutEmptyFields).replaceAll("$1$2: ");
        final String withoutBold = INLINE_BOLD.matcher(withPlainLabels).replaceAll("$1");
        final String withSingleSpaces = MULTIPLE_SPACES.matcher(withoutBold).replaceAll(" ");
        final String withTrimmedLines = LINE_EDGE_SPACES.matcher(withSingleSpaces).replaceAll("");
        return MULTIPLE_LINE_BREAKS.matcher(withTrimmedLines).replaceAll("\n\n");
    }
}
