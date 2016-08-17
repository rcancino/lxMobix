package lx.econta.catalogos

import groovy.util.slurpersupport.GPathResult
import groovy.xml.XmlUtil
import groovy.transform.ToString
import org.grails.databinding.BindingFormat

import org.springframework.security.access.annotation.Secured
import com.luxsoft.lx.contabilidad.PeriodoContable

@Secured(["hasAnyRole('OPERADOR','VENTAS','ADMIN')"])
class SatCatalogoLogController {

	def satCatalogoLogService

    def index(Integer max) { 
    	params.max = Math.min(max ?: 100, 500)
		//params.sort=params.sort?:'mes'
		//params.order='desc'
		def q = SatCatalogoLog.where {rfc == session.empresa.rfc}
			.order('ejercicio', 'desc')
			.order('mes','asc')
		[catalogoList:q.list(params), catalogoListCount: q.count(params)]
    }

    def generar(PeriodoContable periodo){
    	if (periodo == null) {
            flash.message = "Periodo nulo o invalido no se puede generar el catalogo "
            redirect action: 'index'
            return
        }
        log.info("Generando catalogo de cuentas SAT para $session.empresa ${periodo}  $params.comentario")
    	satCatalogoLogService.generar(session.empresa, periodo.ejercicio, periodo.mes,params.comentario)
    	flash.message = "Generado exitosamente el catalogo de cuentas SAT para ${periodo} "
    	redirect action:'index'
    }

    def show(SatCatalogoLog log){
    	[catalogoInstance:log, catalogo:log.asCatalogo()]
    }

    def mostrarXml(SatCatalogoLog log){
		if(log == null){
			flash.message = 'Registro de catalogo nulo no se puede mostrar el xml'
			redirect action: 'index'
			return
		}
		render(text: log.asXmlString(), contentType: "text/xml", encoding: "UTF-8")
	}

	def descargarXml(SatCatalogoLog log){
		if(log == null){
			flash.message = 'No se encuentra el registro de catalogo '
			redirect action: 'index'
			return
		}
		def catalogo = log.asCatalogo()
		String fileName = "${catalogo.rfc}${catalogo.anio}${catalogo.mes}CT.xml"
		response.setContentType("application/octet-stream")
		response.setHeader("Content-disposition", "attachment; filename=\"$fileName\"")
		response.outputStream << new ByteArrayInputStream(log.xml)
		
	}

	def uploadAcuse(RegistroDeAcuseCommand command){
		
		if(command.hasErrors()){
			respond polizaInstance.errors, view:'show'
            return
		}
		
        def xml=request.getFile('file')
        
        if (xml.empty) {
            flash.message = 'Archivo incorrecto (archivo vacío)'
            redirect action:'show',id:log.id
            return
        }
		//log.info('Registro de acuse: '+command)
		def catalogo = command.catalogo
		catalogo.acuse = xml.getBytes()
		catalogo.enviado = command.fecha
		catalogo.save failOnError:true, flush:true
		flash.message="Acuse registrado "
		redirect action:'show',params:[id:catalogo.id]
    }

    def uploadAcuseDeAceptacion(SatCatalogoLog log){
		
		if(log == null){
			flash.message = 'No se encuentra el registro de catalogo '
			redirect action: 'index'
			return
		}
        def xml=request.getFile('file')
        
        if (xml.empty) {
            flash.message = 'Archivo incorrecto (archivo vacío)'
            redirect action:'show',id:log.id
            return
        }
		log.acuseDeAceptacion = xml.getBytes()
		log.save failOnError:true, flush:true
		flash.message="Acuse registrado "
		redirect action:'show',params:[id:log.id]
    }


	

    def descargarAcuseXml(SatCatalogoLog log){
		if(log == null){
			flash.message = 'No se encuentra el registro de catalogo '
			redirect action: 'index'
			return
		}
		
		String fileName = "${log.rfc}${log.ejercicio}${log.mes}CT_Acuse.pdf"
		response.setContentType("application/octet-stream")
		response.setHeader("Content-disposition", "attachment; filename=\"$fileName\"")
		response.outputStream << new ByteArrayInputStream(log.acuse)
		
	}

	def descargarAcuseDeAceptacion(SatCatalogoLog log){
		if(log == null){
			flash.message = 'Registro de catalogo nulo no se puede mostrar el xml'
			redirect action: 'index'
			return
		}
		String fileName = "${log.rfc}${log.ejercicio}${log.mes}CT_AcuseDeAceptacion.pdf"
		render(
            file: log.acuseDeAceptacion, 
            contentType: 'application/pdf',
            fileName:fileName)
	}

    def delete(SatCatalogoLog cat){
    	if(cat == null){
    		flash.message = 'Registro de catalogo nulo no se puede eliminar'
			redirect action: 'index'
			return
    	}
    	cat.delete flush:true
    	flash.message = "Catalog de cuentas ${cat.id} eliminado"
    	redirect action:'index'
    }

    def uploadFile(){

        def xml=request.getFile('file')
        if (xml.empty) {
            flash.message = 'Archivo incorrecto (archivo vacío)'
            redirect action:'index'
            return
        }
        try {
        	log.info 'Importando catalogo: '+params
        	def catalogoLog = satCatalogoLogService.importar(xml.getBytes(),xml.getOriginalFilename())
        	flash.message="Catalogo importado ${catalogoLog.id}"
        	redirect action:'show',params:[id:catalogoLog.id]
        	return
        }
        catch(SatCatalogoLogException e) {
        	log.error(e)
        	flash.message = e.message
        	redirect action: 'index'
        	return
        }
    }
}

@ToString(includeNames=true,includePackage=false)
class RegistroDeAcuseCommand {

	SatCatalogoLog catalogo

	@BindingFormat('dd/MM/yyyy')
    Date fecha=new Date()

    //byte[] file

}
