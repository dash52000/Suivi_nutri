#!/usr/bin/env python3
"""Table CIQUAL (XML de l'ANSES) -> `app/src/main/assets/ciqual.tsv`, la table embarquée dans l'appli.

Usage :
    python3 -I tools/ciqual_xml_vers_tsv.py DOSSIER_XML app/src/main/assets/ciqual.tsv

DOSSIER_XML contient `alim_AAAA_MM_JJ.xml`, `alim_grp_AAAA_MM_JJ.xml` (non lu) et `compo_AAAA_MM_JJ.xml`, tels que
téléchargés sur ciqual.anses.fr (format XML). Les XML (70 Mo) ne sont pas dans le dépôt ; le TSV produit (~300 Ko) y est.
Bibliothèque standard seulement. Le résultat est déterministe : même XML, même fichier.

Colonnes (pour 100 g) : code, nom, groupe, kcal, prot_g, gluc_g, lip_g, sel_g, alcool_g
  - kcal  = const 328 (énergie, règlement UE 1169/2011, kcal) ; à défaut 333 (méthode Jones, kcal) ;
  - prot  = 25003 (azote × facteur de Jones) ; gluc = 31000 ; lip = 40000 ; sel = 10004 (chlorure de sodium) ; alcool = 60000 ;
  - groupe = le code de groupe le plus précis (sous-sous-groupe 6 chiffres, sinon sous-groupe 4, sinon groupe 2) ;
  - « traces » et « < x » (sous le seuil) valent 0 ; « - » (pas de donnée) laisse la case vide ;
  - un aliment sans kcal (143 sur 3 484 en 2025) n'est pas embarqué : il ne servirait à aucune estimation.
Ces codes ont été identifiés le 26/11/2026 sans le fichier des constituants, par cohérence numérique sur les 3 324 aliments
complets : kcal(328) ≈ kJ(327) / 4,184 (rapport médian 0,997) ; régression d'Atwater de kcal sur 25000, 31000, 40000, 60000
et 34100 → 4,02 / 3,98 / 9,02 / 6,99 / 2,09 kcal par g (R² = 0,9991) ; sel(10004) = sodium(10110) × 2,5 (rapport médian 1,000).
"""
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

KCAL, KCAL_JONES = "328", "333"
CONSTITUANTS = {"prot": "25003", "gluc": "31000", "lip": "40000", "sel": "10004", "alcool": "60000"}
UTILES = {KCAL, KCAL_JONES, *CONSTITUANTS.values()}


def nombre(t):
    """Teneur CIQUAL -> float, ou None si pas de donnée."""
    if t is None:
        return None
    t = t.strip().replace(",", ".")
    if t in ("", "-"):
        return None
    if t == "traces" or t.startswith("<"):
        return 0.0
    try:
        return float(t)
    except ValueError:
        return None


def texte(e, balise):
    x = e.find(balise)
    return None if x is None or x.text is None else x.text.strip()


def trouver(dossier, prefixe):
    fichiers = sorted(dossier.glob(f"{prefixe}_*.xml"))
    fichiers = [f for f in fichiers if not f.name.startswith("alim_grp_")] if prefixe == "alim" else fichiers
    if len(fichiers) != 1:
        sys.exit(f"Attendu un seul fichier {prefixe}_*.xml dans {dossier}, trouvé {len(fichiers)}")
    return fichiers[0]


def format1(v):
    return "" if v is None else f"{v:.1f}"


def main(dossier, sortie):
    dossier = Path(dossier)
    f_alim, f_compo = trouver(dossier, "alim"), trouver(dossier, "compo")
    version = re.search(r"(\d{4})_(\d{2})_(\d{2})", f_compo.name)
    version = "-".join(version.groups()) if version else "inconnue"

    aliments = {}
    for _, e in ET.iterparse(f_alim):
        if e.tag == "ALIM":
            sss, ss, g = texte(e, "alim_ssssgrp_code") or "", texte(e, "alim_ssgrp_code") or "", texte(e, "alim_grp_code") or ""
            groupe = sss if sss not in ("", "000000") else ss if ss not in ("", "0000") else g
            aliments[texte(e, "alim_code")] = (texte(e, "alim_nom_fr"), groupe)
            e.clear()

    teneurs = {}
    for _, e in ET.iterparse(f_compo):
        if e.tag == "COMPO":
            c = texte(e, "const_code")
            if c in UTILES:
                teneurs.setdefault(texte(e, "alim_code"), {})[c] = nombre(texte(e, "teneur"))
            e.clear()

    lignes, ecartes = [], 0
    for code in sorted(aliments, key=int):
        nom, groupe = aliments[code]
        t = teneurs.get(code, {})
        kcal = t.get(KCAL) if t.get(KCAL) is not None else t.get(KCAL_JONES)
        if kcal is None:
            ecartes += 1
            continue
        champs = [code, nom, groupe, format1(kcal)] + [format1(t.get(CONSTITUANTS[k])) for k in ("prot", "gluc", "lip", "sel", "alcool")]
        assert all("\t" not in c and "\n" not in c for c in champs), code
        lignes.append("\t".join(champs))

    entete = [
        f"#ciqual\t{version}",
        "#code\tnom\tgroupe\tkcal\tprot_g\tgluc_g\tlip_g\tsel_g\talcool_g",
    ]
    Path(sortie).write_text("\n".join(entete + lignes) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(lignes)} aliments embarqués, {ecartes} écartés (pas de kcal), version {version}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
