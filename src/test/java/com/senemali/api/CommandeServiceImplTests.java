package com.senemali.api;

import com.senemali.api.dto.request.CommandeRequest;
import com.senemali.api.dto.request.DetailCommandeRequest;
import com.senemali.api.dto.response.CommandeResponse;
import com.senemali.api.entity.Agriculteurs;
import com.senemali.api.entity.Commandes;
import com.senemali.api.entity.DetailCommandes;
import com.senemali.api.entity.ProduitAgricoles;
import com.senemali.api.entity.Utilisateurs;
import com.senemali.api.enums.RoleEnum;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandeServiceImplTests {
    @Mock
    private CommandeRepository commandeRepository;
    @Mock
    private ProduitAgricoleRepository produitRepository;
    @Mock
    private IntrantRepository intrantRepository;
    @Mock
    private DetailCommandeRepository detailCommandeRepository;
    @Mock
    private AccessControl accessControl;

    private CommandeServiceImpl service;

    private Utilisateurs buyer;
    private Agriculteurs farmer;
    private ProduitAgricoles product;

    @BeforeEach
    void setUp() {
        DetailCommandeMapper detailMapper = new DetailCommandeMapper();
        CommandeMapper mapper = new CommandeMapper(detailMapper);
        service = new CommandeServiceImpl(
                commandeRepository,
                produitRepository,
                intrantRepository,
                detailCommandeRepository,
                mapper,
                detailMapper,
                accessControl
        );
        buyer = Utilisateurs.builder().id(10L).role(RoleEnum.ACHETEUR).build();
        farmer = Agriculteurs.builder().id(20L).role(RoleEnum.AGRICULTEUR).build();
        product = ProduitAgricoles.builder()
                .idProduit(30L)
                .nomProduit("Mil")
                .prix(250)
                .quantite(8)
                .disponible(true)
                .agriculteur(farmer)
                .build();

        when(accessControl.requireCurrentUser()).thenReturn(buyer);
        when(commandeRepository.save(any(Commandes.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createReservesStockAndPersistsServerPricedDetails() {
        when(produitRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(product));
        CommandeRequest request = request(3);

        CommandeResponse response = service.create(request);

        assertEquals(5, product.getQuantite());
        assertEquals(750, response.getMontantTotal());
        assertEquals(3, response.getDetails().getFirst().getQuantite());
        assertEquals(250, response.getDetails().getFirst().getPrixUnitaire());
        assertEquals(750, response.getDetails().getFirst().getMontant());
        verify(detailCommandeRepository).saveAll(anyList());
        verify(commandeRepository, org.mockito.Mockito.times(2))
                .save(any(Commandes.class));
    }

    @Test
    void createRejectsQuantityAboveStockWithoutPersistingDetails() {
        when(produitRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(product));

        assertThrows(IllegalArgumentException.class, () -> service.create(request(9)));

        assertEquals(8, product.getQuantite());
        verify(detailCommandeRepository, never()).saveAll(anyList());
    }

    @Test
    void cancellingAnInProgressOrderRestoresReservedStock() {
        DetailCommandes detail = DetailCommandes.builder()
                .quantite(3)
                .produit(product)
                .build();
        Commandes order = Commandes.builder()
                .idCommande(40L)
                .status(StatutCommandeEnum.EN_COURS)
                .utilisateur(buyer)
                .detailCommandes(List.of(detail))
                .build();
        product.setQuantite(5);
        when(commandeRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(order));
        when(produitRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(product));

        service.updateStatut(40L, StatutCommandeEnum.ANNULEE);

        assertEquals(8, product.getQuantite());
        assertEquals(StatutCommandeEnum.ANNULEE, order.getStatus());
    }

    private CommandeRequest request(int quantity) {
        DetailCommandeRequest detail = new DetailCommandeRequest();
        detail.setIdProduit(30L);
        detail.setQuantite(quantity);
        CommandeRequest request = new CommandeRequest();
        request.setDetails(List.of(detail));
        return request;
    }
}
