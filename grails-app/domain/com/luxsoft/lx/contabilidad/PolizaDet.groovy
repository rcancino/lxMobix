package com.luxsoft.lx.contabilidad



import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

@EqualsAndHashCode
@ToString(includes='cuenta,debe,haber,concepto,asiento,referencia,entidad,origen',includeNames=true,includePackage=false)
class PolizaDet {
	
	CuentaContable cuenta
	BigDecimal debe
	BigDecimal haber
	String concepto
	String descripcion
	String asiento
	String referencia
	String origen
	String entidad
	
	
	static belongsTo = [poliza:Poliza]

	static hasOne = [cheque: PolizaCheque, transferencia: TransaccionTransferencia, compraNal: TransaccionCompraNal]

    static constraints = {
    	concepto(nullable:true,maxSize:300)
    	asiento nullable:true,maxSize:20
    	referencia nullable:true
		origen(nullable:true,maxSize:20)
		entidad(nullable:true,maxSize:50)
		descripcion(nullable:true,maxSize:255)
		cheque(nullable:true)
		transferencia(nullable:true)
		compraNal nullable:true
    }
	
	static mapping ={
		poliza fetch:'join'
	}
	
	
	
}

