/**
 * Copyright 2019 Project OpenUBL, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Eclipse Public License - v 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.project.openubl.xsender.resources;

import io.github.project.openubl.xsender.exceptions.InvalidXMLFileException;
import io.github.project.openubl.xsender.exceptions.StorageException;
import io.github.project.openubl.xsender.exceptions.UnsupportedDocumentTypeException;
import io.github.project.openubl.xsender.files.FilesManager;
import io.github.project.openubl.xsender.idm.*;
import io.github.project.openubl.xsender.managers.CompanyManager;
import io.github.project.openubl.xsender.managers.DocumentsManager;
import io.github.project.openubl.xsender.models.jpa.CompanyRepository;
import io.github.project.openubl.xsender.models.jpa.UBLDocumentRepository;
import io.github.project.openubl.xsender.models.jpa.entities.CompanyEntity;
import io.github.project.openubl.xsender.models.jpa.entities.UBLDocumentEntity;
import io.github.project.openubl.xsender.models.utils.EntityToRepresentation;
import org.apache.commons.io.IOUtils;
import org.jboss.logging.Logger;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.validation.constraints.NotNull;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Transactional
@ApplicationScoped
public class DefaultCompanyResource implements CompanyResource {

    private static final Logger LOG = Logger.getLogger(DefaultCompanyResource.class);

    @Inject
    CompanyRepository companyRepository;

    @Inject
    UBLDocumentRepository documentRepository;

    @Inject
    DocumentsManager documentsManager;

    @Inject
    FilesManager filesManager;

    @Inject
    CompanyManager companyManager;

    public CompanyRepresentation getCompany(String org) {
        CompanyEntity organizationEntity = companyRepository.findByName(org).orElseThrow(NotFoundException::new);
        return EntityToRepresentation.toRepresentation(organizationEntity);
    }

    public CompanyRepresentation updateCompany(String org, CompanyRepresentation rep) {
        CompanyEntity organizationEntity = companyRepository.findByName(org).orElseThrow(NoClassDefFoundError::new);
        organizationEntity = companyManager.updateCompany(rep, organizationEntity);
        return EntityToRepresentation.toRepresentation(organizationEntity);
    }

    public void updateCompanySUNATCredentials(String org, SunatCredentialsRepresentation rep) {
        CompanyEntity organizationEntity = companyRepository.findByName(org).orElseThrow(NoClassDefFoundError::new);
        companyManager.updateCorporateCredentials(rep, organizationEntity);
    }

    @Override
    public PageRepresentation<DocumentRepresentation> listDocuments(@NotNull String company, String filterText, Integer offset, Integer limit, List<String> sortBy) {
        return null;
    }

    @Override
    public Response createDocument(@NotNull String company, MultipartFormDataInput input) {
        CompanyEntity companyEntity = companyRepository.findByName(company).orElseThrow(NotFoundException::new);

        //

        Map<String, List<InputPart>> uploadForm = input.getFormDataMap();

        List<InputPart> fileInputParts = uploadForm.get("file");

        if (fileInputParts == null) {
            ErrorRepresentation error = new ErrorRepresentation("Form[file] is required");
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }

        byte[] xmlFile = null;

        try {
            for (InputPart inputPart : fileInputParts) {
                InputStream fileInputStream = inputPart.getBody(InputStream.class, null);
                xmlFile = IOUtils.toByteArray(fileInputStream);
            }
        } catch (IOException e) {
            throw new BadRequestException("Could not extract required data from upload/form");
        }

        if (xmlFile == null || xmlFile.length == 0) {
            ErrorRepresentation error = new ErrorRepresentation("Form[file] is empty");
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }

        UBLDocumentEntity documentEntity;
        try {
            documentEntity = documentsManager.createDocumentAndScheduleDelivery(companyEntity, xmlFile);
        } catch (InvalidXMLFileException e) {
            LOG.error(e);
            ErrorRepresentation error = new ErrorRepresentation("Form[file] is not a valid XML file or is corrupted");
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        } catch (UnsupportedDocumentTypeException e) {
            ErrorRepresentation error = new ErrorRepresentation(e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        } catch (StorageException e) {
            LOG.error(e);
            ErrorRepresentation error = new ErrorRepresentation(e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }

        return Response.status(Response.Status.OK)
                .entity(EntityToRepresentation.toRepresentation(documentEntity))
                .build();
    }

    @Override
    public DocumentRepresentation getDocument(@NotNull String company, @NotNull String documentId) {
        return null;
    }

    @Override
    public Response getDocumentFile(@NotNull String company, @NotNull String documentId) {
        UBLDocumentEntity documentEntity = documentRepository.findById(documentId);
        if (documentEntity == null) {
            throw new NotFoundException();
        }

        byte[] file = filesManager.getFileAsBytesAfterUnzip(documentEntity.getStorageFile());
        return Response.ok(file)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + documentEntity.getFilename() + ".xml" + "\""
                )
                .build();
    }

    @Override
    public String getDocumentFileLink(@NotNull String company, @NotNull String documentId) {
        UBLDocumentEntity documentEntity = documentRepository.findById(documentId);
        if (documentEntity == null) {
            throw new NotFoundException();
        }

        return filesManager.getFileLink(documentEntity.getStorageFile());
    }

    @Override
    public Response getDocumentCDR(@NotNull String company, @NotNull String documentId) {
        UBLDocumentEntity documentEntity = documentRepository.findById(documentId);
        if (documentEntity == null) {
            throw new NotFoundException();
        }
        if (documentEntity.getStorageCdr() == null) {
            throw new NotFoundException();
        }

        byte[] file = filesManager.getFileAsBytesWithoutUnzipping(documentEntity.getStorageCdr());
        return Response.ok(file)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + documentEntity.getFilename() + ".zip" + "\""
                )
                .build();
    }

    @Override
    public String getDocumentCDRLink(@NotNull String company, @NotNull String documentId) {
        UBLDocumentEntity documentEntity = documentRepository.findById(documentId);
        if (documentEntity == null) {
            throw new NotFoundException();
        }
        if (documentEntity.getStorageCdr() == null) {
            throw new NotFoundException();
        }

        return filesManager.getFileLink(documentEntity.getStorageCdr());
    }

}

