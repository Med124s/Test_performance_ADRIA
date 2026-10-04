package com.loadpilot.backend.enums;

/**
 * Distribution du jeu de donnees CSV d'un scenario entre utilisateurs virtuels (VU).
 *
 * PER_VIRTUAL_USER : une ligne par VU (ligne n° vu modulo nombre de lignes), la meme pour
 *                    toutes ses iterations - comportement historique.
 * PER_ITERATION    : une nouvelle ligne a chaque iteration, consommee dans l'ordre par tous
 *                    les VU (compteur partage thread-safe), cyclique a la fin du fichier.
 */
public enum CsvDistribution {
    PER_VIRTUAL_USER,
    PER_ITERATION
}
