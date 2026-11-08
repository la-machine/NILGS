-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- ADR-041 D20 (step 11): the administrators' directory of professional accounts is searched ignoring
-- case and accents. search_fold lower-cases a text and maps the accented Latin letters of French and
-- of the national languages' Latin spellings to their base letter, the ligatures to two letters. A
-- plain function, immutable and without a dictionary: the unaccent extension is not needed, and
-- the same folding applies to the term and to the columns. The search compares with position(), not
-- LIKE, so '%' and '_' in a term are ordinary characters.

CREATE FUNCTION search_fold(p_text text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT translate(replace(replace(replace(lower(p_text), 'œ', 'oe'), 'æ', 'ae'), 'ß', 'ss'),
                     'àáâãäåāăąçćčďèéêëēĕėęěìíîïĩīĭįıñńňòóôõöøōŏőŕřśšşťùúûüũūŭůűųýÿŷźżž',
                     'aaaaaaaaacccdeeeeeeeeeiiiiiiiiinnnooooooooorrssstuuuuuuuuuuyyyzzz')
$$;

REVOKE ALL ON FUNCTION search_fold(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION search_fold(text) TO snigf_app;
