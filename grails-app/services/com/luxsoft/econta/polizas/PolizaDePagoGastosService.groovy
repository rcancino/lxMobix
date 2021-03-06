package com.luxsoft.econta.polizas

import grails.transaction.Transactional
import com.luxsoft.lx.tesoreria.Pago
import com.luxsoft.lx.cxp.GastoDet
import com.luxsoft.lx.contabilidad.CuentaContable
import static com.luxsoft.econta.polizas.PolizaUtils.*
import org.apache.commons.lang.StringUtils
import com.luxsoft.lx.core.FormaDePago
import com.luxsoft.lx.contabilidad.*
import com.luxsoft.lx.contabilidad.Poliza
import com.luxsoft.lx.tesoreria.Cheque


@Transactional
class PolizaDePagoGastosService extends AbstractProcesador{



	def generar(def empresa,def fecha,def procesador){

		def pagos = Pago.findAll("from Pago p where p.empresa=? and date(p.fecha)=?  order by p.folio",[empresa,fecha])
		def polizas=[]
		def subTipo=procesador.nombre
		def tipo=procesador.tipo

		pagos.each{ pago ->
			log.info 'Procesando pago: '+pago
			def cancelados = Cheque.findAllByEgresoAndCancelacionIsNotNull(pago)
			if(cancelados){
				polizas<<procesarCancelados(cancelados,empresa,fecha,tipo,subTipo)
			}
			def poliza = Poliza.find(
				"from Poliza p where p.empresa=? and p.subTipo=? and date(p.fecha)=? and p.entidad=? and p.origen=?",
				[empresa,subTipo,fecha,pago.class.name,pago.id])

			if(pago && pago.importe.abs()>0.0){
				if (poliza) {
					if(!poliza.manual){
						poliza.partidas.clear()
						log.info "Actualizando poliza ${subTipo }"+fecha.format('dd/MM/yyyy');
						procesar(poliza,pago)
						poliza.actualizar()
			        	cuadrar(poliza)
			        	depurar(poliza)
						poliza=polizaService.update(poliza)
					}
				} else {
					log.info "GENERANDO poliza ${subTipo } "+fecha.format('dd/MM/yyyy');
					poliza=build(empresa,fecha,tipo,subTipo)
					poliza.entidad=pago.class.name
					poliza.origen=pago.id
		        	procesar(poliza,pago)
		        	poliza.actualizar()
		        	cuadrar(poliza)
		        	depurar(poliza)
					poliza=polizaService.save(poliza)
				}

				polizas << poliza
			}
		}
        return polizas
    }

	void procesar( def poliza,def pago){

		def empresa = poliza.empresa

		def fecha = poliza.fecha
		
		log.info "Generando poliza de egreso pago  ${fecha.format('dd/MM/yyyy')} Pago:${pago}"
		
		def tp=''
		switch(pago.formaDePago) {
			case FormaDePago.CHEQUE:
				if(pago.cheque != null){
					tp='CH-'+pago.cheque.folio
					break
				}else{
					def cheque = Cheque.where{egreso == pago}.find()
					tp='CH-'+cheque.folio
					break
				}
				
			case FormaDePago.TRANSFERENCIA:
				tp='TR-'+pago?.referencia
				break
			default:
			break
		}

		poliza.concepto="${tp} ${pago.aFavor}"
		
		def descripcion=poliza.concepto+' '+pago.requisicion.comentario

		def referencia=pago.referencia
		def asiento = 'PAGO'

		pago.aplicaciones.each { aplicacion ->

			def gasto = aplicacion.cuentaPorPagar

			if(gasto.concepto == 'COMISIONES_BANCARIAS' ){
				return
			} else if (gasto.gastoPorComprobar) {
				def desc = "F. ${gasto.folio} (${gasto.fecha.text()}) ${pago.aFavor} ${pago.requisicion.comentario}"
				asiento = 'GASTO_POR_COMPROBAR'
				cargoAcredoresDiversos(poliza, aplicacion,desc, referencia,asiento)

			} else {
				def desc = "F. ${gasto.folio} (${gasto.fecha.text()}) ${pago.aFavor} ${pago.requisicion.comentario}"
				//Cancelacion de la provision
				cargoProvision(poliza, aplicacion,desc, referencia)
				cargoAIvaAcreditable( poliza,aplicacion,desc,referencia)
				abonoIvaAcreditable(poliza,aplicacion,desc,referencia)
				
				def cxp = aplicacion.cuentaPorPagar
				if(cxp.retensionIsr || cxp.retensionIva){				
					//cargoAbonoARetencionIva(poliza,gasto,cxp,pago,referencia)
					//abonoARetencionIsr(poliza,gasto,cxp,pago,referencia)
				}
			}

		}
		if(pago.importe.abs()>0)
			abonoABancos(poliza,pago,descripcion,referencia,asiento)
	}

	def cargoProvision(def poliza,def aplicacion,descripcion,def referencia){
		
		def pago=aplicacion.pago
		def gasto = aplicacion.cuentaPorPagar
		log.info 'PROVISION:  Cargo a acredores diversosgasto :'+gasto.total

		def cuenta=gasto.proveedor.cuentaContable
		assert cuenta, 'No existe cuenta acredora ya sea para el proveedor o la generica provedores diversos'
		
		def polizaDet = new PolizaDet(
			cuenta:cuenta,
			concepto:cuenta.descripcion,
			debe:gasto.total,
		    haber:0.0,
		    descripcion:StringUtils.substring(descripcion,0,255),
		    asiento:'PAGO',
		    referencia:referencia,
		    origen:gasto.id.toString(),
		    entidad:gasto.class.toString()
		)
		complementoDePago(pago,polizaDet)
		poliza.addToPartidas(polizaDet)
	}

	def cargoAcredoresDiversos(def poliza,def aplicacion,descripcion,def referencia, def asiento){
		
		def pago=aplicacion.pago
		def gasto = aplicacion.cuentaPorPagar
		assert gasto.proveedor.subCuentaOperativa, "No existe la subCuenta operativa para el proveedor: $gasto.proveedor"
		def cuenta = CuentaContable.buscarPorClave(poliza.empresa,'107-' + gasto.proveedor.subCuentaOperativa)
		assert cuenta, 'No existe cuenta acredora ya sea para el proveedor o la generica provedores diversos'
		def polizaDet = new PolizaDet(
			cuenta:cuenta,
			concepto:cuenta.descripcion,
			debe:gasto.total,
		    haber:0.0,
		    descripcion:StringUtils.substring(descripcion,0,255),
		    asiento:asiento,
		    referencia:referencia,
		    origen:gasto.id.toString(),
		    entidad:gasto.class.toString()
		)
		complementoDePago(pago,polizaDet)
		poliza.addToPartidas(polizaDet)
	}

	def cargoAIvaAcreditable(def poliza,def aplicacion,def descripcion,def referencia){

		def gasto = aplicacion.cuentaPorPagar
		def impuesto = gasto.impuesto - gasto.retensionIva
		def pago=aplicacion.pago


		def cuenta=IvaAcreditable(poliza.empresa)
		def polizaDet = new PolizaDet(
			cuenta:cuenta,
			concepto:cuenta.descripcion,
			debe:impuesto,
		    haber:0.0,
		    descripcion:StringUtils.substring(descripcion,0,255),
		    asiento:'PAGO',
		    referencia:referencia,
		    origen:gasto.id.toString(),
		    entidad:gasto.class.toString()
		)
		complementoDePago(pago,polizaDet)
		poliza.addToPartidas(polizaDet)
	}

	def abonoIvaAcreditable(def poliza,def aplicacion,def descripcion,def referencia){

		def gasto = aplicacion.cuentaPorPagar
		def pago=aplicacion.pago
		def impuesto = gasto.impuesto - gasto.retensionIva
		def cuenta=IvaPendienteDeAcreditar(poliza.empresa)
		def polizaDet = new PolizaDet(
			cuenta:cuenta,
			concepto:cuenta.descripcion,
			debe: 0.0,
		    haber: impuesto,
		    descripcion:StringUtils.substring(descripcion,0,255),
		    asiento:'PAGO',
		    referencia:referencia,
		    origen:gasto.id.toString(),
		    entidad:gasto.class.toString()
		)
		complementoDePago(pago,polizaDet)
		poliza.addToPartidas(polizaDet)
	}

	

	def abonoABancos(def poliza,def pago,descripcion,def referencia, asiento = 'PAGO' ){
		def cuenta=pago.cuenta.cuentaContable
		assert cuenta,"La cuenta de banco ${pago.cuenta} no tiene cuenta contable asignada"
		def desc=""
		pago.aplicaciones.each{ aplicacion ->
			def gasto = aplicacion.cuentaPorPagar
			if(gasto){
				desc += "F. ${gasto.folio} ${gasto.fecha.text()} ${pago.aFavor}"
			}
		}

		def det=new PolizaDet(
			cuenta:cuenta,
			concepto:cuenta.descripcion,
		    debe:0.0,
		    haber:pago.importe.abs(),
		    descripcion:poliza.concepto,
		    asiento: asiento,
		    referencia:referencia,
		    origen:pago.id.toString(),
		    entidad:pago.class.toString()
		)
		complementoDePago(pago,det)
		poliza.addToPartidas(det)
	}

	def abonoARetencionIsr(def poliza,def gasto,def cxp,def pago,def referencia){
		def desc = "F. ${cxp.folio} ${cxp.fecha.text()} ${pago.aFavor} ${pago.requisicion.comentario}"
		def det = null
		if(cxp.retensionIsr){
			def concepto=gasto.concepto
			
			if(concepto=='HONORARIOS_ASIMILADOS'){
				det = abonoA(
					poliza,
					RetencionIsrHonorariosAsimilados(poliza.empresa),
					cxp.retensionIsr.abs(),
					desc,
					'PAGO',
					referencia,
					cxp
				)
			}
			else if(concepto == 'HONORARIOS_CON_RETENCION'){
				det = abonoA(
					poliza,
					RetencionIsrHonorarios(poliza.empresa),
					cxp.retensionIsr.abs(),
					desc,
					'PAGO',
					referencia,
					cxp
				)
			}
			else if(concepto == 'SERVICIOS_PROFESIONALES'){
				det = abonoA(
					poliza,
					RetencionIsrServiciosProfesionales(poliza.empresa),
					cxp.retensionIsr.abs(),
					desc,
					'PAGO',
					referencia,
					cxp
				)
					
			}
			else if(concepto == 'RETENCION_PAGOS'){
				det = abonoA(
					poliza,
					RetencionIsrDividendos(poliza.empresa),
					cxp.retensionIsr.abs(),
					desc,
					'PAGO',
					referencia,
					cxp
				)
			}
		}
		if(det!= null){
			complementoDePago(pago,det)
		}
	}

	def cargoAbonoARetencionIva(def poliza,def gasto,def cxp,def pago,def referencia){
		
		def det = null
		if(cxp.retensionIva){
			def desc = "F. ${cxp.folio} ${cxp.fecha.text()} ${pago.aFavor} ${pago.requisicion.comentario}"
			
			det = cargoA(
				poliza,
				IvaRetenidoPendient(poliza.empresa),
				cxp.retensionIva.abs(),
				desc,
				'PAGO',
				referencia,
				cxp
			)

			det = abonoA(
				poliza,
				ImpuestoRetenidoDeIva(poliza.empresa),
				cxp.retensionIva.abs(),
				desc,
				'PAGO',
				referencia,
				cxp
			)
		}
		if(det!= null){
			complementoDePago(pago,det)
		}
	}

	def procesarCancelados(def cancelados,def empresa,def fecha,def tipo,def subTipo){

		cancelados.each{ cheque ->

			log.info "GENERANDO Poliza de cheque cancelado $cheque"
			def pago=cheque.egreso
			def poliza = Poliza.find(
				"from Poliza p where p.empresa=? and p.subTipo=? and date(p.fecha)=? and p.entidad=? and p.origen=?",
				[empresa,subTipo,fecha,cheque.class.name,cheque.id])
			
			if(!poliza){
				
				poliza=build(empresa,fecha,tipo,subTipo)
				poliza.entidad=cheque.class.name
				poliza.origen=cheque.id
				
			}

			poliza.partidas.clear()

			def tp='CH-'+cheque.folio

			poliza.concepto="${tp} CANCELADO ${pago.aFavor}"
			
			def descripcion=poliza.concepto

			def referencia=cheque.folio.toString()

			def cuenta=pago.cuenta.cuentaContable
			assert cuenta,"La cuenta de banco ${pago.cuenta} no tiene cuenta contable asignada"
			
			def det=new PolizaDet(
				cuenta:cuenta,
				concepto:cuenta.descripcion,
			    debe:0.0,
			    haber:0.0,
			    descripcion:StringUtils.substring(descripcion,0,255),
			    asiento:'CHEQUE',
			    referencia:referencia,
			    origen:cheque.id.toString(),
			    entidad:cheque.class.toString()
			)
			 
			/*
			assert cheque.cuenta
			//assert pago.aFavor, 'No esta registrado aFavor de quien está el pago '+pago.id
			def rfc=pago.rfc?:pago.requisicion.proveedor.rfc
			def polCheque=new PolizaCheque(
				polizaDet:det,
				numero:cheque.folio.toString(),
				bancoEmisorNacional:cheque.cuenta.banco.bancoSat,
				cuentaOrigen:cheque.cuenta.numero,
				fecha:cheque.dateCreated,
				beneficiario:pago.aFavor,
				rfc:rfc,
				monto:0.0
			)
			det.cheque=polCheque
			*/
			poliza.addToPartidas(det)
			poliza=polizaService.save(poliza)
		}
	}

	def complementoDePago(def pago, def det){
		
		if(pago.formaDePago==FormaDePago.CHEQUE){
			
			def cheque=pago.cheque?pago.cheque:null
			assert cheque,'Pago sin cheque registrado'+pago.id
			assert cheque.cuenta
			assert pago.aFavor, 'No esta registrado aFavor de quien está el pago '+pago.id
			def rfc=pago.rfc?:pago.requisicion.proveedor.rfc
			def polCheque=new PolizaCheque(
				polizaDet:det,
				numero:cheque.folio.toString(),
				bancoEmisorNacional:cheque.cuenta.banco.bancoSat,
				cuentaOrigen:cheque.cuenta.numero,
				fecha:pago.fecha,
				beneficiario:pago.aFavor,
				rfc:rfc,
				monto:pago.importe
			)
			det.cheque=polCheque
		}

		if(pago.formaDePago==FormaDePago.TRANSFERENCIA){
			
			if(pago.bancoDestino && pago.cuentaDestino){
				log.info('Generando registro de Transaccion transferencia SAT para pago: '+pago.id)
				assert pago.aFavor, 'No esta registrado aFavor de quien está el pago '+pago.id
				def rfc=pago.rfc?:pago.requisicion.proveedor.rfc
				def transferencia=new TransaccionTransferencia(
					polizaDet:det,
					bancoOrigenNacional:pago.cuenta.banco.bancoSat,
					cuentaOrigen:pago.cuenta.numero,
					fecha:pago.fecha,
					beneficiario:pago.aFavor,
					rfc:rfc,
					monto:pago.importe,
					bancoDestinoNacional: pago.bancoDestino,
					cuentaDestino: pago.cuentaDestino
				)
				det.transferencia=transferencia
			}
			
			
		}
	}

	def cargoA(def poliza,def cuenta,def importe,def descripcion,def asiento,def referencia,def entidad){
		def det=new PolizaDet(
			cuenta:cuenta,
        	concepto:cuenta.descripcion,
            debe:importe.abs(),
            haber:0.0,
            descripcion:StringUtils.substring(descripcion,0,255),
            asiento:asiento,
            referencia:referencia,
            origen:entidad.id.toString(),
            entidad:entidad.class.toString()
		)
		poliza.addToPartidas(det)
		return det;
	}

	def abonoA(def poliza,def cuenta,def importe,def descripcion,def asiento,def referencia,def entidad){
		def det=new PolizaDet(
			cuenta:cuenta,
        	concepto:cuenta.descripcion,
            debe:0.0,
            haber:importe.abs(),
            descripcion:StringUtils.substring(descripcion,0,255),
            asiento:asiento,
            referencia:referencia,
            origen:entidad.id.toString(),
            entidad:entidad.class.toString()
		)
		poliza.addToPartidas(det)
		return det
	}

}
