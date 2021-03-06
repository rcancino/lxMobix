package com.luxsoft.lx.cxp

import grails.transaction.Transactional
import com.luxsoft.lx.utils.MonedaUtils

import groovy.io.FileType
import org.apache.commons.io.IOUtils
import com.luxsoft.lx.core.*
import java.text.SimpleDateFormat
import com.luxsoft.lx.contabilidad.CuentaContable

import javax.xml.bind.JAXBContext
import javax.xml.bind.JAXBException
import javax.xml.bind.Marshaller
import javax.xml.bind.Unmarshaller
import org.apache.commons.lang.exception.ExceptionUtils
import com.luxsoft.cfdi.Acuse
import java.text.DecimalFormat
import groovy.xml.*


@Transactional
class GastoService {

    def springSecurityService

    def  consultaService

    def save(Gasto gasto){
        gasto.with{
            def user=springSecurityService.getCurrentUser().username
            creadoPor=delegate.creadoPor?:user
            modificadoPor=user
            partidas=delegate.partidas?:[] as Set
        } 
        gasto.save failOnError:true
        return gasto
    }

    def update(Gasto gasto){
        gasto.with{
            modificadoPor=springSecurityService.getCurrentUser().username
        }
        actualizarImportes gasto
        gasto.save failOnError:true
        return gasto
    }

    def agregarConcepto(Gasto gasto,GastoDet det){

        //if(det.cuentaContable==null)
            //det.cuentaContable=gasto.cuentaContable
        if(!det.concepto)
            det.concepto=gasto.concepto
        gasto.addToPartidas(det)
        //actualizarImportes gasto
        //gasto.save failOnError:true
        update(gasto)
    }

    def eleiminarPartida(GastoDet det){
        Gasto gasto=det.gasto
        gasto.removeFromPartidas(det)
        // actualizarImportes()
        // gasto=gasto.save failOnError:true
        update(gasto)
    }

    def actualizarPartida(GastoDet det){
        Gasto gasto=det.gasto
        return update(gasto)
    }


    def cargarComprobanteFislcal(){

    }

    def actualizarImportes(Gasto gasto){
        if(gasto.partidas){
            gasto.with{
                partidas*.actualizarImportes()
                importe=partidas.sum(0.0,{it.importe}) 
                subTotal=importe-descuento
                if(impuestoTasa>1) impuestoTasa/=100
                impuesto=MonedaUtils.calcularImpuesto(subTotal,impuestoTasa?:MonedaUtils.IVA)

            }
            // gasto.partidas*.actualizarImportes()
            // gasto.importe=gasto.partidas.sum(0.0,{it.importe})
            // gasto.impuesto=MonedaUtils.calcularImpuesto(gasto.importe,impuesto?:MonedaUtils.IVA)
        }
        gasto.with{
            total=subTotal+impuesto
        }
    }

    def importar2(File xmlFile){
        SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        def xml = new XmlSlurper().parse(xmlFile)
        def comprobante=xml.attributes()
    }

    def asignarCfdi(Gasto gasto,File xmlFile){

        println "Importando gasto"
        def xml = new XmlSlurper().parse(xmlFile)
        SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

        if(xml.name()=='Comprobante'){
            def data=xml.attributes()
            def version = data.version
            if(version == null){
                version = data.Version
            }
        }

          log.debug 'Comprobante:  '+xml.attributes()  
            def receptor=xml.breadthFirst().find { it.name() == 'Receptor'}
            def cuenta=CuentaContable.findByClave('600-0000')

            assert cuenta,'No se ha declarado la cuenta general de gastos'
            if('Receptor'==receptor.name()){
                def empresa=Empresa.findByRfc(receptor.attributes()['Rfc'])
                }
            // assert empresa,'No existe empresa para: '+receptor.name()+ ' RFC: '+receptor.attributes()['rfc']+ 'Archivo: '+xmlFile.name
            log.debug 'Importando Gasto/Compra para '+empresa
            
            def emisorNode= xml.breadthFirst().find { it.name() == 'Emisor'}
            if(empresa==null){
                empresa=Empresa.findByRfc(emisorNode.attributes()['Rfc'])
                assert empresa,'No existe empresa que pueda registrar este CFDI'
            }

            if('Emisor'==emisorNode.name()){

                def nombre=emisorNode.attributes()['Nombre']
                def rfc=emisorNode.attributes()['Rfc']
                
                def proveedor=Proveedor.findByEmpresaAndRfc(empresa,rfc)

                if(!proveedor){
                    log.debug "Alta de proveedor: $nombre ($rfc)"
                    def domicilioFiscal=emisorNode.breadthFirst().find { it.name() == 'DomicilioFiscal'}
                    def dom=domicilioFiscal.attributes()
                    def direccion=new Direccion(
                        calle:dom.calle,
                        numeroExterior:dom.noExterior,
                        numeroInterior:dom.noInterior,
                        colonia:dom.colonia,
                        municipio:dom.municipio,
                        estado:dom.estado,
                        pais:dom.pais,
                        codigoPostal:dom.codigoPostal)
                    proveedor=new Proveedor(nombre:nombre,rfc:rfc,direccion:direccion,empresa:empresa)
                    proveedor.save failOnError:true,flush:true   
                }
            }
            


                    def serie=xml.attributes()['Serie']
                    def folio=xml.attributes()['Folio']
                    def fecha=df.parse(xml.attributes()['Fecha'])
                    def timbre=xml.breadthFirst().find { it.name() == 'TimbreFiscalDigital'}
                    def uuid=timbre.attributes()['UUID'] 
                    def subTotal=data['SubTotal'] as BigDecimal
                    
                    gasto.fecha = fecha
                    gasto.importe=data['SubTotal'] as BigDecimal
                    gasto.descuento=data['Descuento'] as BigDecimal?:0.0
                    gasto.subTotal=subTotal
                    gasto.total=data['Total'] as BigDecimal
                    gasto.cfdiXmlFileName=xmlFile.name
                    gasto.uuid=uuid
                    gasto.serie=serie
                    gasto.folio=folio
                    gasto.cfdiXml=xmlFile.getBytes()

            def traslados=xml.breadthFirst().find { it.name() == 'Traslados'}

            if(traslados){
                traslados.children().each{ t->
                    if(t.attributes()['Impuesto']=='IVA'){
                        def tasa=t.attributes()['Tasa'] as BigDecimal
                        gasto.impuestoTasa=tasa
                        gasto.impuesto=t.attributes()['Importe'] as BigDecimal
                    }
                }
            }

        def retenciones=xml.breadthFirst().find { it.name() == 'Retenciones'}
                    
                    if(retenciones){
                        retenciones.breadthFirst().each{
                            def map=it.attributes()
                            if(map.impuesto=='IVA'){
                                def imp=map.importe as BigDecimal
                                def tasa=imp*100/subTotal
                                gasto.retensionIva=imp
                                gasto.retensionIvaTasa=tasa
                            }
                            if(map.impuesto=='ISR'){
                                def imp=map.importe as BigDecimal
                                def tasa=imp*100/subTotal
                                gasto.retensionIsr=imp
                                gasto.retensionIsrTasa=tasa
                            }
                        }    
                    }
       
        def conceptos=comprobante.breadthFirst().find { it.name() == 'Conceptos'}

        conceptos.children().each{
            def model=it.attributes()
            def det=new GastoDet(
                         cuentaContable:cuenta,
                         descripcion:model['Descripcion'],
                         unidad:model['ClaveUnidad'],
                         cantidad:model['Cantidad'],
                         valorUnitario:model['ValorUnitario'],
                         importe:model['Importe'],
                         comentario:"ClaveProdServ: ${model.ClaveProdServ} NoIdenticioacion: ${model.NoIdentificacion}"
                    )
            
            if(it.Impuestos?.Traslados?.Traslado[0]){
                println it.Impuestos.Traslados.Traslado[0].attributes()
            }
            if(it.Impuestos?.Retenciones?.Retencion[0]){
                def retencion = it.Impuestos.Retenciones.Retencion[0].attributes()
                if(retencion.Impuesto == '001'){
                    det.retencionIsr = gasto.retensionIsr = new BigDecimal(retencion.Importe)
                    det.retencionIsrTasa = new BigDecimal(retencion.TasaOCuota)
                }
                if(retencion.Impuesto == '002'){
                    det.retencionIva = new BigDecimal(retencion.Importe)
                    det.retencionIvaTasa = new BigDecimal(retencion.TasaOCuota)
                }
                
            }
            gasto.addToPartidas(det)
            
        }

        if(gasto.id )
            return update(gasto)
        return  save(gasto)
}

    def buildGastoFromCfdiV33(def comprobante, def xmlFile, Gasto gasto){
        
        assert comprobante.name() == 'Comprobante'
        assert comprobante.attributes()['Version'] == '3.3', 'No es la version de cfdi 3.3'        

        def receptor = comprobante.breadthFirst().find { it.name() == 'Receptor'}
        def emisor= comprobante.breadthFirst().find { it.name() == 'Emisor'}
        def timbre=comprobante.breadthFirst().find { it.name() == 'TimbreFiscalDigital'}

        def empresa=Empresa.findByRfc(receptor.attributes().Rfc)

        def proveedor=Proveedor.findByEmpresaAndRfc(empresa,emisor.attributes().Rfc)
        def serie=comprobante.attributes()['Serie']
        def folio=comprobante.attributes()['Folio']
        def fecha=Date.parse("yyyy-MM-dd'T'HH:mm:ss",comprobante.attributes()['Fecha'])
        def uuid=timbre.attributes()['UUID']
        def subTotal = comprobante.attributes()['SubTotal'] as BigDecimal
        def total = comprobante.attributes()['Total'] as BigDecimal
        def descuento = comprobante.attributes()['Descuento'] as BigDecimal
        gasto.empresa= empresa
        gasto.proveedor= proveedor
        gasto.fecha= fecha
        gasto.vencimiento= fecha+1
        gasto.importe= subTotal
        gasto.descuento= descuento?:0.0
        gasto.subTotal= subTotal
        gasto.total= total
        gasto.comentario ='Importacion de gasto'
        gasto.cfdiXmlFileName = xmlFile.name
        gasto.uuid = uuid
        gasto.serie = serie
        gasto.folio = folio
        gasto.cfdiXml = xmlFile.getBytes()
        gasto.gastoPorComprobar = false

        // Impuestos trasladados
        def traslados=comprobante.breadthFirst().find { it.name() == 'Traslados'}
        if(traslados){
            traslados.children().each{ t->
               if(t.attributes()['Impuesto']=='002'){ // IVA
                   
                   def tasa=t.attributes()['TasaOCuota'] as BigDecimal
                   gasto.impuestoTasa=tasa

                   if(!gasto.impuestoTasa){
                    gasto.impuestoTasa=0
                   }

                   gasto.impuesto=t.attributes()['Importe'] as BigDecimal
               }
            }
        }else{
            gasto.impuesto =0
            gasto.impuestoTasa=0
        }

        // Impuestos retendidos
        def retenciones=comprobante.breadthFirst().find { it.name() == 'Retenciones'}
        if(retenciones){
            retenciones.breadthFirst().each{
                def map=it.attributes()
                if(map.impuesto=='002'){
                    def imp = map.Importe as BigDecimal
                    def tasa = map['TasaOCuota'] as BigDecimal
                    gasto.retensionIva = imp
                    gasto.retensionIvaTasa = tasa
                }
                if(map.impuesto=='ISR'){
                    def imp=map.importe as BigDecimal
                    def tasa = map['TasaOCuota'] as BigDecimal
                    gasto.retensionIsr = imp
                    gasto.retensionIsrTasa = tasa
                }
            }
        }else{
            gasto.retensionIva = 0.0
           gasto.retensionIvaTasa = 0.0
        }

        // Conceptos
        if(gasto.partidas)
            gasto.partidas.clear()
        def cuenta=CuentaContable.findByClave('600-0000')
        def conceptos=comprobante.breadthFirst().find { it.name() == 'Conceptos'}
        conceptos.children().each{
            def model=it.attributes()
            def det=new GastoDet(
                         cuentaContable:cuenta,
                         descripcion:model['Descripcion'],
                         unidad:model['ClaveUnidad'],
                         cantidad:model['Cantidad'],
                         valorUnitario:model['ValorUnitario'],
                         importe:model['Importe'],
                         comentario:"ClaveProdServ: ${model.ClaveProdServ} NoIdenticioacion: ${model.NoIdentificacion}"
                    )
            
            if(it.Impuestos?.Traslados?.Traslado[0]){
                println it.Impuestos.Traslados.Traslado[0].attributes()
            }
            if(it.Impuestos?.Retenciones?.Retencion[0]){
                def retencion = it.Impuestos.Retenciones.Retencion[0].attributes()
                if(retencion.Impuesto == '001'){
                    det.retencionIsr = gasto.retensionIsr = new BigDecimal(retencion.Importe)
                    det.retencionIsrTasa = new BigDecimal(retencion.TasaOCuota)
                }
                if(retencion.Impuesto == '002'){
                    det.retencionIva = new BigDecimal(retencion.Importe)
                    det.retencionIvaTasa = new BigDecimal(retencion.TasaOCuota)
                }
                
            }
            gasto.addToPartidas(det)
            
        }
        if(gasto.id )
            return update(gasto)
        return  save(gasto)
    }

    def importar(File xmlFile){

        println "Importando gasto"
        def xml = new XmlSlurper().parse(xmlFile)
        SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

        if(xml.name()=='Comprobante'){
            def data=xml.attributes()
            def version = data.version
            if(version == null){
                version = data.Version
            }
            
            if(version == '3.3'){
                //throw new RuntimeException('Version de CFDI no esta lista')
                return buildGastoFromCfdiV33(xml, xmlFile, new Gasto())

            }
            
            log.debug 'Comprobante:  '+xml.attributes()  
            def receptor=xml.breadthFirst().find { it.name() == 'Receptor'}
            def cuenta=CuentaContable.findByClave('600-0000')
            assert cuenta,'No se ha declarado la cuenta general de gastos'
            if('Receptor'==receptor.name()){
                def empresa=Empresa.findByRfc(receptor.attributes()['rfc'])

               // assert empresa,'No existe empresa para: '+receptor.name()+ ' RFC: '+receptor.attributes()['rfc']+ 'Archivo: '+xmlFile.name
                log.debug 'Importando Gasto/Compra para '+empresa
                
                def emisorNode= xml.breadthFirst().find { it.name() == 'Emisor'}
                if(empresa==null){
                    empresa=Empresa.findByRfc(emisorNode.attributes()['rfc'])
                    assert empresa,'No existe empresa que pueda registrar este CFDI'
                }
                
                if('Emisor'==emisorNode.name()){

                    def nombre=emisorNode.attributes()['nombre']
                    def rfc=emisorNode.attributes()['rfc']
                    
                    def proveedor=Proveedor.findByEmpresaAndRfc(empresa,rfc)
                    if(!proveedor){
                        log.debug "Alta de proveedor: $nombre ($rfc)"
                        def domicilioFiscal=emisorNode.breadthFirst().find { it.name() == 'DomicilioFiscal'}
                        def dom=domicilioFiscal.attributes()
                        def direccion=new Direccion(
                            calle:dom.calle,
                            numeroExterior:dom.noExterior,
                            numeroInterior:dom.noInterior,
                            colonia:dom.colonia,
                            municipio:dom.municipio,
                            estado:dom.estado,
                            pais:dom.pais,
                            codigoPostal:dom.codigoPostal)
                        proveedor=new Proveedor(nombre:nombre,rfc:rfc,direccion:direccion,empresa:empresa)
                        proveedor.save failOnError:true,flush:true
                        
                    }
                    def serie=xml.attributes()['serie']
                    def folio=xml.attributes()['folio']
                    def fecha=df.parse(xml.attributes()['fecha'])
                    def timbre=xml.breadthFirst().find { it.name() == 'TimbreFiscalDigital'}
                    def uuid=timbre.attributes()['UUID']
                    def gasto=Gasto.findByUuid(uuid)
                    def subTotal=data['subTotal'] as BigDecimal
                    if(gasto==null){
                        gasto=new Gasto(
                            empresa:empresa,
                            proveedor:proveedor,
                            fecha:fecha,
                            vencimiento:fecha+1,
                            importe:data['subTotal'] as BigDecimal,
                            descuento:data['descuento'] as BigDecimal?:0.0,
                            subTotal:data['subTotal'] as BigDecimal,
                            total:data['total'] as BigDecimal,
                            comentario:'Importacion de gasto',
                            xmlFileName:xmlFile.name,
                            uuid:uuid,
                            serie:serie,
                            folio:folio,
                            cfdiXml:xmlFile.getBytes(),
                            gastoPorComprobar: false
                        )
                        def traslados=xml.breadthFirst().find { it.name() == 'Traslados'}
                        if(traslados){
                            traslados.children().each{ t->
                                //println it.name()+ ' Val: '+it.attributes() 
                                if(t.attributes()['impuesto']=='IVA'){
                                    println 'IVA: '+t.name()+ ' Val: '+t.attributes() 
                                    def tasa=t.attributes()['tasa'] as BigDecimal
                                    gasto.impuestoTasa=tasa
                                    gasto.impuesto=t.attributes()['importe'] as BigDecimal
                                }
                            }
                        }
                        def retenciones=xml.breadthFirst().find { it.name() == 'Retenciones'}
                        if(retenciones){
                            retenciones.breadthFirst().each{
                                def map=it.attributes()
                                if(map.impuesto=='IVA'){
                                   def imp=map.importe as BigDecimal
                                   def tasa=imp*100/subTotal
                                   gasto.retensionIva=imp
                                   gasto.retensionIvaTasa=tasa
                                   
                                }
                                if(map.impuesto=='ISR'){
                                   def imp=map.importe as BigDecimal
                                   def tasa=imp*100/subTotal
                                   gasto.retensionIsr=imp
                                   gasto.retensionIsrTasa=tasa
                                }
                            }
                        }
                        
                        
                        def conceptos=xml.breadthFirst().find { it.name() == 'Conceptos'}
                        conceptos.children().each{
                            def model=it.attributes()
                            def det=new GastoDet(
                                cuentaContable:cuenta,
                                descripcion:model['descripcion'],
                                unidad:model['unidad'],
                                cantidad:model['cantidad'],
                                valorUnitario:model['valorUnitario'],
                                importe:model['importe'],
                                comentario:"Concepto importado  ${xmlFile.name}"
                            )
                            if(!gasto.partidas){
                                det.retencionIsr=gasto.retensionIsr
                                det.retencionIsrTasa=gasto.retensionIsrTasa
                                det.retencionIva=gasto.retensionIva
                                det.retencionIvaTasa=gasto.retensionIvaTasa
                            }
                            gasto.addToPartidas(det)

                        }
                        
                        gasto=save(gasto)
                        
                    }
                    else{
                        log.info 'Gasto ya registrado...'+uuid

                    }
                    return gasto
                }
            }

        }
        return null
    }

    def getXml(Gasto gasto){
        ByteArrayInputStream is=new ByteArrayInputStream(gasto.cfdiXml)
        def xml = new XmlSlurper().parse(is)
        return xml
    }

    def getCfdiXml(Gasto gasto){
        ByteArrayInputStream is=new ByteArrayInputStream(gasto.cfdiXml)
        def xml = new XmlSlurper().parse(is)
        return XmlUtil.serialize(xml)
    }

    def Acuse validarEnElSat(Gasto gasto){
        try {
            def emisor=gasto.proveedor.rfc
            def receptor=gasto.empresa.rfc
            def xml=getXml(gasto)
            def data=xml.attributes()
            def total=data['total']
            def version = data.version
            if(version==null){
                version = data.Version
                total = data.Total
                log.info('Validando CFDI version: '+version);
            } 
            
            //DecimalFormat format=new DecimalFormat("####.000000")
            //String stotal=format.format(gasto.total)
            String qq="?re=$emisor&rr=$receptor&tt=$total&id=${gasto.uuid.toUpperCase()}"
            log.info 'Validando en SAT Expresion impresa: '+qq

            Acuse acuse=consultaService.consulta(qq)
            gasto.acuseEstado=acuse.getEstado().getValue().toString()
            gasto.acuseCodigoEstatus=acuse.getCodigoEstatus().getValue().toString()
            registrarAcuse(gasto,acuse)
            gasto.save()
            return acuse
        }
        catch(Exception e) {
            String msg=ExceptionUtils.getRootCauseMessage(e)
            gasto.acuseEstado='SIN VALIDAR'
            gasto.acuseCodigoEstatus=msg
        }
        
        
    }

    def  registrarAcuse(Gasto gasto,Acuse acuse){
        try {
            JAXBContext context = JAXBContext.newInstance(Acuse.class);
            Marshaller m = context.createMarshaller();
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            ByteArrayOutputStream out=new ByteArrayOutputStream()
            m.marshal(acuse,out);
            gasto.acuse=out.toByteArray()
        } catch (JAXBException e) {
            e.printStackTrace();
        }
    }

    def  toXml(Acuse acuse){
        try {
            JAXBContext context = JAXBContext.newInstance(Acuse.class);
            Marshaller m = context.createMarshaller();
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            StringWriter w=new StringWriter();
            m.marshal(acuse,w);
            return w.toString();
        } catch (JAXBException e) {
            e.printStackTrace();
        }
    }

    def  toAcuse(byte[] data){
        try {
            JAXBContext context = JAXBContext.newInstance(Acuse.class)
            Unmarshaller u = context.createUnmarshaller()
            ByteArrayInputStream is=new ByteArrayInputStream(data)
            Object o = u.unmarshal( is )
            return (Acuse)o
            
        } catch (JAXBException e) {
            e.printStackTrace();
        }
    }

    def corregirProveedores(){
        def gastos=Gasto.findAll().each{
            def proveedor=it.proveedor
            if(proveedor.empresa.id!=it.empresa.id){
                log.info "Corrigiendo proveedor para el gasto ${it}"
                def empresa=it.empresa
                def found=Proveedor.findByEmpresaAndRfc(empresa,proveedor.rfc)
                if(!found){
                    println "Dando de alta al proveedor ${proveedor.rfc} para la empresa:${empresa}"
                    def direccion=new Direccion(
                        calle:proveedor.direccion.calle,
                        numeroInterior:proveedor.direccion.numeroInterior,
                        numeroExterior:proveedor.direccion.numeroExterior,
                        colonia:proveedor.direccion.colonia,
                        municipio:proveedor.direccion.municipio,
                        codigoPostal:proveedor.direccion.codigoPostal,
                        estado:proveedor.direccion.estado,
                        pais:proveedor.direccion.pais
                        )
                    found=new Proveedor(
                        empresa:empresa,
                        nombre:proveedor.nombre,
                        direccion:direccion,
                        rfc:proveedor.rfc,
                        nacional:proveedor.nacional,
                        email:proveedor.email,
                        www:proveedor.www
                    )
                    found.save flush:true
                }
                it.proveedor=found
                it.save flush:true
            }
        }
    }

}

class GastoException extends RuntimeException{
    String message
    Gasto gasto
}
