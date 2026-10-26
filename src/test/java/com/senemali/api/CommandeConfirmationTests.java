package com.senemali.api;

import com.senemali.api.dto.response.CommandeResponse;
import com.senemali.api.entity.Agriculteurs;
import com.senemali.api.entity.Commandes;
import com.senemali.api.entity.DetailCommandes;
import com.senemali.api.entity.ProduitAgricoles;
import com.senemali.api.entity.Utilisateurs;
import com.senemali.api.enums.StatutCommandeEnum;
import com.senemali.api.mapper.CommandeMapper;
import com.senemali.api.mapper.DetailCommandeMapper;
import com.senemali.api.repository.CommandeRepository;
import com.senemali.api.repository.DetailCommandeRepository;
import com.senemali.api.repository.IntrantRepository;
import com.senemali.api.repository.ProduitAgricoleRepository;
import com.senemali.api.security.AccessControl;
import com.senemali.api.serviceImpl.CommandeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommandeConfirmationTests {
    @Mock private CommandeRepository commandeRepository;
    @Mock private ProduitAgricoleRepository produitRepository;
    @Mock private IntrantRepository intrantRepository;
    @Mock private DetailCommandeRepository detailCommandeRepository;
    @Mock private CommandeMapper mapper;
    @Mock private DetailCommandeMapper detailMapper;
    @Mock private AccessControl accessControl;

    @InjectMocks
    private CommandeServiceImpl commandeService;

    private Commandes commande;
    private Utilisateurs vendeur;
    private Utilisateurs client;
    private DetailCommandes detail;
    private ProduitAgricoles produit;

    @BeforeEach
    void setUp() {
        commande = mock(Commandes.class);
        vendeur = mock(Utilisateurs.class);
        client = mock(Utilisateurs.class);
        detail = mock(DetailCommandes.class);
        produit = mock(ProduitAgricoles.class);
        Agriculteurs agriculteur = mock(Agriculteurs.class);

        when(vendeur.getId()).thenReturn(20L);
        when(agriculteur.getId()).thenReturn(20L);
        when(detail.getProduit()).thenReturn(produit);
        when(produit.getAgriculteur()).thenReturn(agriculteur);
        when(commande.getDetailCommandes()).thenReturn(List.of(detail));
        when(commandeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(commande));
        when(accessControl.requireCurrentUser()).thenReturn(vendeur);
    }

    @Test
    void vendeurCanConfirmAnOrderInProgress() {
        CommandeResponse response = new CommandeResponse();
        when(commande.getStatus()).thenReturn(StatutCommandeEnum.EN_COURS);
        when(commandeRepository.save(commande)).thenReturn(commande);
        when(mapper.toResponse(commande)).thenReturn(response);

        assertSame(response, commandeService.confirmer(1L));

        verify(commande).setStatus(StatutCommandeEnum.CONFIRMEE);
        verify(commandeRepository).save(commande);
    }

    @Test
    void vendeurCanRefuseAnOrderInProgress() {
        CommandeResponse response = new CommandeResponse();
        when(commande.getStatus()).thenReturn(StatutCommandeEnum.EN_COURS);
        when(detail.getQuantite()).thenReturn(2);
        when(produit.getIdProduit()).thenReturn(30L);
        when(produitRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(produit));
        when(produit.getQuantite()).thenReturn(3);
        when(commandeRepository.save(commande)).thenReturn(commande);
        when(mapper.toResponse(commande)).thenReturn(response);

        assertSame(response, commandeService.refuser(1L));

        verify(commande).setStatus(StatutCommandeEnum.REFUSEE);
        verify(produit).setQuantite(5);
        verify(commandeRepository).save(commande);
    }

    @Test
    void orderCannotBeMarkedDeliveredBeforeConfirmation() {
        when(client.getId()).thenReturn(10L);
        when(commande.getUtilisateur()).thenReturn(client);
        when(commande.getStatus()).thenReturn(StatutCommandeEnum.EN_COURS);

        assertThrows(IllegalArgumentException.class,
                () -> commandeService.updateStatut(1L, StatutCommandeEnum.LIVREE));

        verify(commandeRepository, never()).save(any());
    }
}
