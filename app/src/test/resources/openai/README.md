# Réponses d'OpenAI simulées

Écrites à la main d'après la documentation officielle de la Responses API (consultée le 2 novembre 2026).
Aucune ne vient d'un appel réel et aucune clé n'est en jeu. Les éléments `reasoning` portent un faux
`encrypted_content` : l'app doit les renvoyer tels quels au tour suivant.

`recherche-web-reponse.json` : réponse après une recherche web, au format décrit par le guide de l'outil `web_search` :
deux `web_search_call` (`search`, puis `open_page`), un message dont l'`output_text` porte une annotation `url_citation`
(plage `start_index`/`end_index` du balisage « ([site](url)) »), et `tool_usage.web_search.num_requests`.
