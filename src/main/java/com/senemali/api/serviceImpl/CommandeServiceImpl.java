package com.senemali.api.serviceImpl;

import com.senemali.api.dto.request.CommandeRequest;
import com.senemali.api.dto.request.DetailCommandeRequest;
import com.senemali.api.dto.response.CommandeResponse;
import com.senemali.api.entity.*;
import com.senemali.api.enums.RoleEnum;
import com.senemali.api.enums.StatutCommandeEnum;
import com.senemali.api.exception.ResourceNotFoundException;
import com.senemali.api.mapper.CommandeMapper;
import com.senemali.api.mapper.DetailCommandeMapper;
import com.senemali.api.repository.*;
import com.senemali.api.security.AccessControl;
import com.senemali.api.service.CommandeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommandeServiceImpl implements CommandeService {
    private final CommandeRepository commandeRepository;
    private final ProduitAgricoleRepository produitRepository;
    private final IntrantRepository intrantRepository;
    private final DetailCommandeRepository detailCommandeRepository;
    private final CommandeMapper mapper;
    private final DetailCommandeMapper detailMapper;
    private final AccessControl accessControl;

    @Override
    @Transactional
    public CommandeResponse create(CommandeRequest request) {
        Utilisateurs utilisateur = accessControl.requireCurrentUser();
        boolean acheteur = utilisateur.getRole() == RoleEnum.ACHETEUR;
        boolean agriculteur = utilisateur.getRole() == RoleEnum.AGRICULTEUR;
        if (!acheteur && !agriculteur) {
            throw new AccessDeniedException("Seuls un acheteur ou un agriculteur peuvent passer une commande");
        }
        Commandes commande = Commandes.builder()
                .dateCommande(LocalDateTime.now())
                .status(StatutCommandeEnum.EN_COURS)
                .montantTotal(0)
                .utilisateur(utilisateur)
                .build();
        commande = commandeRepository.save(commande);
        List<DetailCommandes> details = new ArrayList<>();
        int montantTotal = 0;
        Long vendeurId = null;
        for (DetailCommandeRequest dr : request.getDetails()) {
            if ((dr.getIdProduit() == null) == (dr.getIdIntrant() == null)) {
                throw new IllegalArgumentException("Chaque ligne doit cibler un produit ou un intrant, mais pas les deux");
            }
            if (acheteur != (dr.getIdProduit() != null)) {
                throw new IllegalArgumentException(acheteur
                        ? "Un acheteur peut commander uniquement des produits agricoles"
                        : "Un agriculteur peut commander uniquement des intrants");
            }
            DetailCommandes detail = detailMapper.toEntity(dr);
            detail.setCommande(commande);
            int prixUnitaire;
            if (dr.getIdProduit() != null) {
                ProduitAgricoles produit = produitRepository.findByIdForUpdate(dr.getIdProduit())
                        .orElseThrow(() -> new ResourceNotFoundException("Produit non trouvé"));
                if (!Boolean.TRUE.equals(produit.getDisponible())) {
                    throw new IllegalArgumentException("Le produit n'est pas disponible");
                }
                if (dr.getQuantite() > produit.getQuantite()) {
                    throw new IllegalArgumentException("La quantité demandée dépasse le stock disponible");
                }
                Long currentSellerId = produit.getAgriculteur().getId();
                if (vendeurId != null && !vendeurId.equals(currentSellerId)) {
                    throw new IllegalArgumentException("Une commande doit concerner un seul vendeur");
                }
                vendeurId = currentSellerId;
                prixUnitaire = produit.getPrix();
                produit.setQuantite(Math.subtractExact(produit.getQuantite(), dr.getQuantite()));
                detail.setProduit(produit);
            } else {
                Intrants intrant = intrantRepository.findByIdForUpdate(dr.getIdIntrant())
                        .orElseThrow(() -> new ResourceNotFoundException("Intrant non trouvé"));
                if (!Boolean.TRUE.equals(intrant.getDisponible())) {
                    throw new IllegalArgumentException("L'intrant n'est pas disponible");
                }
                if (intrant.getStatutIntrant() != com.senemali.api.enums.StatutValidationFournisseur.VALIDE
                        || !Boolean.TRUE.equals(intrant.getFournisseur().getStatut())) {
                    throw new IllegalArgumentException("L'intrant ou son fournisseur n'est pas validé");
                }
                if (dr.getQuantite() > intrant.getQuantite()) {
                    throw new IllegalArgumentException("La quantité demandée dépasse le stock disponible");
                }
                Long currentSellerId = intrant.getFournisseur().getId();
                if (vendeurId != null && !vendeurId.equals(currentSellerId)) {
                    throw new IllegalArgumentException("Une commande doit concerner un seul vendeur");
                }
                vendeurId = currentSellerId;
                prixUnitaire = intrant.getPrix();
                intrant.setQuantite(Math.subtractExact(intrant.getQuantite(), dr.getQuantite()));
                detail.setIntrant(intrant);
            }
            detail.setPrixUnitaire(prixUnitaire);
            detail.setMontant(Math.multiplyExact(dr.getQuantite(), prixUnitaire));
            details.add(detail);
            montantTotal = Math.addExact(montantTotal, detail.getMontant());
        }
        detailCommandeRepository.saveAll(details);
        commande.setMontantTotal(montantTotal);
        commande.setDetailCommandes(details);
        return mapper.toResponse(commandeRepository.save(commande));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommandeResponse> findAll() {
        return commandeRepository.findAll().stream().map(mapper::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public CommandeResponse findById(Long id) {
        return mapper.toResponse(commandeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Commande non trouvée")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommandeResponse> findMine() {
        Utilisateurs utilisateur = accessControl.requireCurrentUser();
        Map<Long, Commandes> commandes = new LinkedHashMap<>();
        if (utilisateur.getRole() == RoleEnum.ACHETEUR || utilisateur.getRole() == RoleEnum.AGRICULTEUR) {
            commandeRepository.findByUtilisateur_Id(utilisateur.getId())
                    .forEach(commande -> commandes.put(commande.getIdCommande(), commande));
        }
        if (utilisateur.getRole() == RoleEnum.AGRICULTEUR) {
            commandeRepository.findByAgriculteurVendeurId(utilisateur.getId())
                    .forEach(commande -> commandes.put(commande.getIdCommande(), commande));
        } else if (utilisateur.getRole() == RoleEnum.FOURNISSEUR) {
            commandeRepository.findByFournisseurVendeurId(utilisateur.getId())
                    .forEach(commande -> commandes.put(commande.getIdCommande(), commande));
        }
        return commandes.values().stream().map(mapper::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long countMySales() {
        Utilisateurs utilisateur = accessControl.requireCurrentUser();
        return switch (utilisateur.getRole()) {
            case AGRICULTEUR -> commandeRepository.countByAgriculteurVendeurId(utilisateur.getId());
            case FOURNISSEUR -> commandeRepository.countByFournisseurVendeurId(utilisateur.getId());
            default -> throw new AccessDeniedException(
                    "Seuls un agriculteur ou un fournisseur peuvent consulter le nombre de commandes reçues"
            );
        };
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommandeResponse> findByUtilisateur(Long idUtilisateur) {
        return commandeRepository.findByUtilisateur_Id(idUtilisateur).stream()
                .map(mapper::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public CommandeResponse confirmer(Long id) {
        Commandes commande = commandeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Commande non trouvée"));
        verifierVendeurCommandeEnCours(commande, "confirmer", "confirmée");
        commande.setStatus(StatutCommandeEnum.CONFIRMEE);
        return mapper.toResponse(commandeRepository.save(commande));
    }

    @Override
    @Transactional
    public CommandeResponse refuser(Long id) {
        Commandes commande = commandeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Commande non trouvée"));
        verifierVendeurCommandeEnCours(commande, "refuser", "refusée");
        restaurerStock(commande);
        commande.setStatus(StatutCommandeEnum.REFUSEE);
        return mapper.toResponse(commandeRepository.save(commande));
    }

    private void verifierVendeurCommandeEnCours(Commandes commande, String action, String actionPasse) {
        Utilisateurs utilisateurCourant = accessControl.requireCurrentUser();
        if (!isSellerForCommande(commande, utilisateurCourant)) {
            throw new AccessDeniedException("Seul le vendeur concerné peut " + action + " la commande");
        }
        if (commande.getStatus() != StatutCommandeEnum.EN_COURS) {
            throw new IllegalArgumentException("Seule une commande en cours peut être " + actionPasse);
        }
    }

    @Override
    @Transactional
    public CommandeResponse updateStatut(Long id, StatutCommandeEnum statut) {
        Commandes commande = commandeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Commande non trouvée"));
        Utilisateurs utilisateurCourant = accessControl.requireCurrentUser();
        boolean client = commande.getUtilisateur().getId().equals(utilisateurCourant.getId());
        boolean vendeur = isSellerForCommande(commande, utilisateurCourant);

        if (statut == StatutCommandeEnum.ANNULEE) {
            if (commande.getStatus() != StatutCommandeEnum.EN_COURS) {
                throw new IllegalArgumentException("Seule une commande en cours peut être annulée");
            }
            if (!client) {
                throw new AccessDeniedException("Seul le client ayant passé la commande peut l'annuler");
            }
        } else if (statut == StatutCommandeEnum.LIVREE) {
            if (commande.getStatus() != StatutCommandeEnum.CONFIRMEE) {
                throw new IllegalArgumentException("Seule une commande confirmée peut être marquée comme livrée");
            }
            if (!vendeur) {
                throw new AccessDeniedException("Seul le vendeur concerné peut marquer la commande comme livrée");
            }
        } else {
            throw new IllegalArgumentException("Ce statut ne peut pas être défini via cet endpoint");
        }
        if (statut == StatutCommandeEnum.ANNULEE) {
            restaurerStock(commande);
        }
        commande.setStatus(statut);
        return mapper.toResponse(commandeRepository.save(commande));
    }

    private void restaurerStock(Commandes commande) {
        for (DetailCommandes detail : commande.getDetailCommandes()) {
            if (detail.getProduit() != null) {
                ProduitAgricoles produit = produitRepository
                        .findByIdForUpdate(detail.getProduit().getIdProduit())
                        .orElseThrow(() -> new ResourceNotFoundException("Produit non trouvé"));
                produit.setQuantite(Math.addExact(produit.getQuantite(), detail.getQuantite()));
            } else if (detail.getIntrant() != null) {
                Intrants intrant = intrantRepository
                        .findByIdForUpdate(detail.getIntrant().getIdIntrant())
                        .orElseThrow(() -> new ResourceNotFoundException("Intrant non trouvé"));
                intrant.setQuantite(Math.addExact(intrant.getQuantite(), detail.getQuantite()));
            }
        }
    }

    private boolean isSellerForCommande(Commandes commande, Utilisateurs utilisateur) {
        return commande.getDetailCommandes().stream().anyMatch(detail ->
                (detail.getProduit() != null
                        && detail.getProduit().getAgriculteur().getId().equals(utilisateur.getId()))
                        || (detail.getIntrant() != null
                        && detail.getIntrant().getFournisseur().getId().equals(utilisateur.getId())));
    }
}
